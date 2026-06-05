package io.github.stozo04.openloop.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Produces a time-reversed copy of a video's trimmed window using a two-pass MediaCodec pipeline.
 *
 * Media3 1.10.x ships no reverse effect and FFmpegKit is retired, so reversal is done by hand
 * (the verified rationale + algorithm live in `docs/active/boomerang-rollout/RESEARCH-reverse-video.md`,
 * mirroring the MIT-licensed sisik.eu reference at github.com/sixo/reverse-video):
 *
 *  - **Pass 1** transcodes the trim window `[trimStartMs, trimEndMs]` to an intermediate MP4 in which
 *    *every* frame is a keyframe (`KEY_I_FRAME_INTERVAL = 0`). A normal MP4 only carries sparse sync
 *    samples, so you cannot seek to an arbitrary frame; making every frame independently decodable is
 *    what lets pass 2 walk the video backwards a frame at a time.
 *  - **Pass 2** seeks to each frame from last → first, decodes it onto the encoder's input [Surface],
 *    and re-stamps the presentation time as `endUs - originalUs` so the output plays in reverse.
 *
 * Audio is dropped (a reversed boomerang has no meaningful audio). The reverser is suspending,
 * cancellable, and idempotent: a cache key of `sha1(<source-abs-path>_<trimStart>_<trimEnd>)` is
 * checked first, so an identical request returns the cached file without re-encoding. All codecs /
 * muxer are released in a `finally` block, and [kotlinx.coroutines.CancellationException] propagates
 * cleanly so coroutine cancellation tears the pipeline down (Lesson 013).
 *
 * @param scratchDir the working directory for the intermediate + cached reversed files
 *                   (`cacheDir/scratch/reversed/`). Created on demand.
 */
class VideoReverser(
    private val scratchDir: File,
) {

    /** Pass 1 surface encoder that published an output format; reused for pass 2. */
    @Volatile
    private var lastSurfaceEncoderName: String? = null

    /**
     * Reverse [source] over `[trimStartMs, trimEndMs]`, returning the reversed MP4 [File].
     * Throws [java.io.IOException] / [MediaCodec.CodecException] on a pipeline failure (the caller
     * converts that into a user-facing "couldn't save" path); honors coroutine cancellation.
     */
    suspend fun reverse(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        scratchDir.mkdirs()
        val output = File(scratchDir, "${cacheKey(source, trimStartMs, trimEndMs)}.mp4")
        if (output.exists()) {
            // Content-validated, never `length() > 0` alone: a wedged pass 2 can leave a ~598-byte
            // zero-sample shell behind (S23/API 33), which would otherwise poison every retry of
            // this trim window (reverse-output-validation spec §5.2).
            val cached = ReverseOutputValidator.validateReversedOutput(output)
            if (cached.valid) {
                ReversePreviewLog.i(
                    "reverse.cache_hit",
                    "key=${output.name} bytes=${cached.fileBytes} samples=${cached.sampleCount}",
                )
                onProgress(1f)
                return@withContext output
            }
            ReversePreviewLog.e(
                "reverse.cache_invalid",
                "key=${output.name} reason=${cached.reason} bytes=${cached.fileBytes} — deleting poisoned cache",
            )
            output.delete()
        }

        val intermediate = File(scratchDir, "${ReverseScratchJanitor.INTERMEDIATE_PREFIX}${UUID.randomUUID()}.mp4")
        registerIntermediate(intermediate)
        lastSurfaceEncoderName = null
        logReverseStart(source, trimStartMs, trimEndMs)
        ReversePreviewLog.i(
            "reverse.start",
            "trim=${trimStartMs}..${trimEndMs}ms samsung=${isSamsungDevice()}",
        )
        // Second pass when [shouldRetryMediaCodecContention] allows (Samsung dequeue churn or
        // surface-released on any device — Crashlytics b09e527 on Android 17 (OS) emulator), or when
        // a pass produced ZERO samples (S23/API 33 wedge) — that retry skips the Samsung
        // software-decoder carve-out and uses the platform-default decoder instead (spec §5.8).
        val maxAttempts = SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS
        var lastFailure: Throwable? = null
        // Sticky across reverses in this process: once a device wedges (zero-frame pass 2), every
        // HW-encoder attempt will wedge again — skip straight to the software encoder instead of
        // paying a doomed attempt + retry delay on each preview/save (S23/API 33, RESEARCH.md §7c).
        var zeroFrameFailure = zeroFrameEncoderWedgeSticky
        try {
            for (attempt in 0 until maxAttempts) {
                // Zero-frame retry pairing (spec §5.8, revised on-device 2026-06-04): on the S23/API 33
                // the SW-decoder→HW-encoder pairing starves pass 2 (0 samples) and the HW-decoder→
                // HW-encoder pairing throws CodecException 0xe at queueInputBuffer — so the retry
                // flips the ENCODER to software (decoder stays per normal selection). The contention
                // retry path is unchanged.
                val preferSoftwareEncoder = zeroFrameFailure
                try {
                    coroutineContext.ensureActive()
                    if (attempt > 0) {
                        lastSurfaceEncoderName = null
                        intermediate.delete()
                        output.delete()
                        ReversePreviewLog.i(
                            if (preferSoftwareEncoder) "reverse.zero_frame_retry" else "reverse.contention_retry",
                            "attempt=${attempt + 1}/$maxAttempts delayMs=${SAMSUNG_CODEC_CONTENTION_RETRY.inWholeMilliseconds} " +
                                "preferSoftwareEncoder=$preferSoftwareEncoder",
                        )
                        delay(SAMSUNG_CODEC_CONTENTION_RETRY)
                    } else {
                        ReversePreviewLog.d(
                            "reverse.settle",
                            "preReverseDelayMs=${PRE_REVERSE_CODEC_SETTLE.inWholeMilliseconds}",
                        )
                        delay(PRE_REVERSE_CODEC_SETTLE)
                    }
                    ReversePreviewLog.d("reverse.pass1.start", "dest=${intermediate.name}")
                    transcodeToAllKeyframes(source, trimStartMs, trimEndMs, intermediate, preferSoftwareEncoder) { frac ->
                        onProgress(frac * 0.5f)
                    }
                    ReversePreviewLog.i("reverse.pass1.done", "intermediateBytes=${intermediate.length()}")
                    coroutineContext.ensureActive()
                    ReversePreviewLog.d(
                        "reverse.pass2.start",
                        "dest=${output.name} pass1Encoder=$lastSurfaceEncoderName",
                    )
                    reverseAllKeyframeVideo(intermediate, output, preferSoftwareEncoder) { frac ->
                        onProgress(0.5f + frac * 0.5f)
                    }
                    // A pass 2 starved of frames exits "cleanly" with a sample-less shell that the
                    // Transformer rejects 3 seconds later as a cryptic asset-loader error (S23/API
                    // 33, RESEARCH.md §2). Validate before declaring success; the in-pipeline
                    // counter in reverseAllKeyframeVideo throws earlier — this is the backstop.
                    val validation = ReverseOutputValidator.validateReversedOutput(output)
                    if (!validation.valid) {
                        ReversePreviewLog.e(
                            "reverse.output_invalid",
                            "reason=${validation.reason} bytes=${validation.fileBytes} samples=${validation.sampleCount}",
                        )
                        output.delete()
                        throw ReverseOutputInvalidException(
                            "Reversed output invalid: ${validation.reason} (${output.name})",
                            validation,
                        )
                    }
                    ReversePreviewLog.i(
                        "reverse.pass2.done",
                        "outBytes=${validation.fileBytes} samples=${validation.sampleCount}",
                    )
                    ReversePreviewLog.i(
                        "reverse.complete",
                        "pass1Encoder=$lastSurfaceEncoderName out=${output.name} attempts=${attempt + 1}",
                    )
                    onProgress(1f)
                    return@withContext output
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    lastFailure = t
                    output.delete()
                    // Zero-sample output (S23/API 33): retry once with the software encoder
                    // (spec §5.8). Never loops past maxAttempts; a second zero-frame run fails loudly.
                    if (t is ReverseOutputInvalidException && attempt < maxAttempts - 1) {
                        zeroFrameFailure = true
                        zeroFrameEncoderWedgeSticky = true
                        continue
                    }
                    if (!shouldRetryMediaCodecContention(t, attempt, maxAttempts, isSamsungDevice())) {
                        throw t
                    }
                }
            }
            throw lastFailure ?: IllegalStateException("reverse failed without exception")
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                ReversePreviewLog.e(
                    "reverse.failed",
                    "pass=unknown ${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
            }
            // Don't leave a half-written reversed file behind on any failure (incl. cancellation).
            output.delete()
            throw t
        } finally {
            unregisterIntermediate(intermediate)
            intermediate.delete()
        }
    }

    private fun registerIntermediate(file: File) {
        activeIntermediatePaths.add(file.absolutePath)
    }

    private fun unregisterIntermediate(file: File) {
        activeIntermediatePaths.remove(file.absolutePath)
    }

    // ── Pass 1: trim + re-encode so every frame is an I-frame (seekable per-frame) ──────────────

    private suspend fun transcodeToAllKeyframes(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        dest: File,
        preferSoftwareEncoder: Boolean = false,
        onProgress: (Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null

        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = selectVideoTrack(extractor)
            require(trackIndex >= 0) { "No video track in ${source.name}" }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)

            val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            // Encode at the source's NATIVE size (only evened for 4:2:0). We must NOT downscale here:
            // the decoder renders its native-size frames onto the encoder's input Surface, and if that
            // Surface is a different size the producer→consumer scale is device-/codec-dependent and on
            // the software-codec fallback path it corrupts the reversed frames to green macroblocks
            // (camera clips never hit this — they're already ≤ the cap, so the old cappedToShortSide()
            // returned the SAME dims and there was no mismatch; a >1080p import is the first to exercise
            // the downscale). The resolution cap now lives ONLY in the Media3 render (Presentation),
            // which downscales the forward AND reversed clips together via a correct GL pipeline — so the
            // halves still match. See docs/lessons_learned (reverse-downscale-surface-mismatch).
            val width = evenDown(srcWidth)
            val height = evenDown(srcHeight)
            val frameRate = inputFormat.frameRateOrDefault().coerceAtMost(MAX_PASS1_ENCODE_FPS)
            val minEncodeIntervalUs = 1_000_000L / frameRate
            // Capture the source's rotation hint, then NEUTRALIZE it on the format handed to the
            // decoder. In Surface-output mode MediaCodec auto-applies KEY_ROTATION, and whether the
            // decoder->encoder-input-surface path bakes that into the pixels is device-dependent
            // (developer.android.com/reference/android/media/MediaCodec). We re-stamp the hint on the
            // muxer below, so an auto-rotating decoder would DOUBLE-rotate the reversed half. Clearing
            // it forces coded-orientation pixels + a metadata-only hint — structurally identical to
            // the source — so Media3 rotates the forward and reversed halves symmetrically. Verify on
            // a real portrait recording (see docs/lessons_learned/HANDOFF + SECOND-REVIEW notes).
            val rotationDegrees = inputFormat.rotationDegreesOrZero()
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            inputFormat.requestSdrToneMapping() // HDR/10-bit source → SDR for the 8-bit AVC encoder
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                (trimEndMs - trimStartMs) * 1000L
            }

            val encoderFormat = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, estimateBitRate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                // Every frame an I-frame → pass 2 can seek to any frame. This is the whole point of pass 1.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
                applySdrBt709ColorMetadata()
            }

            ReversePreviewLog.d(
                "pass1.encodeFormat",
                "${width}x$height @${frameRate}fps iInterval=0 bitrate=${encoderFormat.getInteger(MediaFormat.KEY_BIT_RATE)}",
            )
            val pipeline = openSurfaceCodecPipeline(
                encoderFormat = encoderFormat,
                decoderMime = inputFormat.getString(MediaFormat.KEY_MIME)!!,
                decoderFormat = inputFormat,
                preferSoftwareEncoder = preferSoftwareEncoder,
            )
            decoder = pipeline.decoder
            encoder = pipeline.encoder
            inputSurface = pipeline.inputSurface
            val pass1Encoder = requireNotNull(encoder)

            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)

            val startUs = trimStartMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // SEEK_TO_PREVIOUS_SYNC can land on a sync point *before* the trim start — skip those
            // samples so pass 1 does not EOS immediately with zero frames (which wedged the loop).
            while (extractor.sampleTime in 0 until startUs) {
                if (!extractor.advance()) break
            }
            val endUs = trimEndMs * 1000L
            val spanUs = (endUs - startUs).coerceAtLeast(1L)

            var encodedFrames = 0
            var skippedFrames = 0
            runDecodeEncodeLoop(
                extractor = extractor,
                decoder = decoder,
                encoder = pass1Encoder,
                muxer = muxer,
                startUs = startUs,
                endUs = endUs,
                durationUs = durationUs,
                minEncodeIntervalUs = minEncodeIntervalUs,
                onSamplePts = { sampleUs -> ((sampleUs - startUs).toFloat() / spanUs).coerceIn(0f, 1f) },
                onProgress = onProgress,
                // Pass 1 keeps original timestamps (re-based to 0 at the trim start).
                remapPtsUs = { sampleUs -> (sampleUs - startUs).coerceAtLeast(0L) },
                onFrameEncoded = { encodedFrames++ },
                onFrameSkipped = { skippedFrames++ },
            )
            val pass1Msg = "reverse pass1: ${width}x$height, encoded=$encodedFrames, skipped=$skippedFrames, fpsCap=$frameRate"
            Log.d(TAG, pass1Msg)
            ReversePreviewLog.i("pass1.loop.done", pass1Msg.removePrefix("reverse pass1: "))
        } finally {
            runCatching { decoder?.stop() }; decoder?.release()
            runCatching { encoder?.stop() }; encoder?.release()
            inputSurface?.release()
            runCatching { muxer?.stop() }; muxer?.release()
            extractor.release()
        }
    }

    // ── Pass 2: walk frames last → first, re-stamping PTS so playback runs in reverse ───────────

    private suspend fun reverseAllKeyframeVideo(
        source: File,
        dest: File,
        preferSoftwareEncoder: Boolean = false,
        onProgress: (Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null

        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = selectVideoTrack(extractor)
            require(trackIndex >= 0) { "No video track in intermediate ${source.name}" }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)

            val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = inputFormat.frameRateOrDefault()
            // The intermediate carries the source's rotation hint (pass 1 wrote it); forward it so
            // the final reversed file keeps it too. Same neutralize-then-re-stamp dance as pass 1:
            // strip KEY_ROTATION from the decoder format (no Surface-mode auto-rotate / double-rotate)
            // and re-apply it on the muxer below.
            val rotationDegrees = inputFormat.rotationDegreesOrZero()
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            // Pass-1's intermediate is already SDR AVC, so this is normally a no-op here; kept for
            // symmetry and to cover the (rare) case of a non-tone-mapped intermediate.
            inputFormat.requestSdrToneMapping()

            // Collect every frame's presentation time (the intermediate is all-keyframe, so each is seekable).
            val frameTimesUs = ArrayList<Long>()
            var syncCount = 0
            run {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val t = extractor.sampleTime
                    if (t < 0L) break
                    frameTimesUs.add(t)
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncCount++
                    if (!extractor.advance()) break
                }
            }
            // Pass 2 seeks to and decodes each frame standalone, which is only correct if EVERY frame is
            // a sync sample (pass 1 requested KEY_I_FRAME_INTERVAL=0). If syncCount < total the encoder
            // didn't honor all-keyframe and the reverse will stutter/corrupt — log it so we can tell.
            Log.d(TAG, "reverse pass2: ${width}x$height, frames=${frameTimesUs.size}, sync=$syncCount")
            require(frameTimesUs.isNotEmpty()) { "Intermediate ${source.name} has no frames" }
            val endUs = frameTimesUs.last()

            val encoderFormat = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, estimateBitRate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL)
                applySdrBt709ColorMetadata()
            }
            val pass2Pipeline = openSurfaceCodecPipeline(
                encoderFormat = encoderFormat,
                decoderMime = inputFormat.getString(MediaFormat.KEY_MIME)!!,
                decoderFormat = inputFormat,
                preferSoftwareEncoder = preferSoftwareEncoder,
            )
            decoder = pass2Pipeline.decoder
            encoder = pass2Pipeline.encoder
            inputSurface = pass2Pipeline.inputSurface
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)

            // Separate BufferInfos so a decoder dequeue can't clobber the encoder-drain state.
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var muxerTrack = -1
            var muxerStarted = false
            val total = frameTimesUs.size

            // Feed frames from last → first; each input is stamped with PTS = endUs - sampleUs so the
            // surface frame plays in reverse. Decoder pipeline latency means an output may arrive a few
            // inputs after its input — we render EVERY decoded output (carrying its own reversed PTS),
            // so out-of-lockstep dequeues are fine. Decoder EOS triggers the encoder EOS.
            var feedIndex = frameTimesUs.size - 1
            var inputDone = false
            var outputDone = false
            var emitted = 0
            var muxedSamples = 0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIndex = runMediaCodecCancellable { decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) }
                    if (inIndex >= 0) {
                        if (feedIndex < 0) {
                            runMediaCodecCancellable {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            inputDone = true
                        } else {
                            val sampleUs = frameTimesUs[feedIndex]
                            extractor.seekTo(sampleUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val buffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                runMediaCodecCancellable {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                inputDone = true
                            } else {
                                runMediaCodecCancellable {
                                    decoder.queueInputBuffer(inIndex, 0, size, endUs - sampleUs, 0)
                                }
                            }
                            feedIndex--
                        }
                    }
                }

                // Render each decoded frame onto the encoder surface (carrying its reversed PTS).
                val outIndex = runMediaCodecCancellable { decoder.dequeueOutputBuffer(decoderInfo, DEQUEUE_TIMEOUT_US) }
                if (outIndex >= 0) {
                    val render = decoderInfo.size > 0
                    runMediaCodecCancellable { decoder.releaseOutputBuffer(outIndex, render) }
                    if (render) {
                        emitted++
                        onProgress((emitted.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        runMediaCodecCancellable { encoder.signalEndOfInputStream() }
                    }
                }

                muxerStarted = drainToMuxer(
                    encoder, muxer, encoderInfo,
                    onTrackReady = { fmt -> muxerTrack = muxer.addTrack(fmt); muxer.start(); true },
                    muxerTrack = { muxerTrack },
                    muxerStarted = muxerStarted,
                    endOfStream = inputDone,
                    onSampleWritten = { muxedSamples++ },
                ).also { if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && it) outputDone = true }
            }

            // The S23/API-33 wedge: the loop above can exit "cleanly" — encoder emits only a
            // format-change + empty EOS — having muxed nothing (rendered=$emitted stays 0 too).
            // Without this throw the empty shell is returned as success, cached, and the Transformer
            // fails 3s later with an unrelated-looking asset-loader error (RESEARCH.md §2–§4).
            if (muxedSamples == 0) {
                ReversePreviewLog.e(
                    "reverse.pass2.empty",
                    "muxed=0 rendered=$emitted planned=$total dest=${dest.name}",
                )
                throw ReverseOutputInvalidException(
                    "Reverse pass 2 muxed 0 samples ($total planned, $emitted rendered) for ${dest.name}",
                )
            }
        } finally {
            runCatching { decoder?.stop() }; decoder?.release()
            runCatching { encoder?.stop() }; encoder?.release()
            inputSurface?.release()
            runCatching { muxer?.stop() }; muxer?.release()
            extractor.release()
        }
    }

    /**
     * Pass-1 decode→encode loop: pump samples in `[trimStart, trimEnd]` from [extractor] through the
     * surface-coupled [decoder]/[encoder] into [muxer], re-basing each frame's PTS via [remapPtsUs].
     */
    private suspend fun runDecodeEncodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
        minEncodeIntervalUs: Long = 0L,
        onSamplePts: (Long) -> Float,
        onProgress: (Float) -> Unit,
        remapPtsUs: (Long) -> Long,
        onFrameEncoded: () -> Unit = {},
        onFrameSkipped: () -> Unit = {},
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerTrack = -1
        var muxerStarted = false
        var inputDone = false
        var decoderDone = false
        val timeoutUs = DEQUEUE_TIMEOUT_US
        var lastEncodedSampleUs = -1L
        var loopIterations = 0
        // True when the extractor is exhausted but decoder EOS has not been queued yet.
        var pendingDecoderEos = false

        while (!decoderDone) {
            currentCoroutineContext().ensureActive()
            if (++loopIterations > MAX_DECODE_LOOP_ITERATIONS) {
                throw IllegalStateException(
                    "Pass-1 decode loop exceeded $MAX_DECODE_LOOP_ITERATIONS iterations " +
                        "(last sampleUs=${extractor.sampleTime}, encoded=$lastEncodedSampleUs)",
                )
            }

            if (!inputDone) {
                // Advance past pre-trim samples and dense frames *without* dequeuing decoder input
                // buffers — every dequeued buffer must be queued exactly once or MediaCodec wedges.
                if (!pendingDecoderEos) {
                    while (true) {
                        val sampleUs = extractor.sampleTime
                        if (sampleUs < 0L || sampleUs > endUs) {
                            pendingDecoderEos = true
                            break
                        }
                        if (sampleUs < startUs) {
                            if (!extractor.advance()) {
                                pendingDecoderEos = true
                            }
                            continue
                        }
                        if (
                            pass1SampleAction(
                                sampleUs = sampleUs,
                                lastEncodedSampleUs = lastEncodedSampleUs,
                                endUs = endUs,
                                minEncodeIntervalUs = minEncodeIntervalUs,
                            ) == Pass1SampleAction.ENCODE
                        ) {
                            break
                        }
                        onFrameSkipped()
                        val prevUs = sampleUs
                        if (!extractor.advance()) {
                            pendingDecoderEos = true
                            break
                        }
                        if (extractor.sampleTime == prevUs) {
                            pendingDecoderEos = true
                            break
                        }
                    }
                }

                val inIndex = runMediaCodecCancellable { decoder.dequeueInputBuffer(timeoutUs) }
                if (inIndex >= 0) {
                    if (pendingDecoderEos) {
                        runMediaCodecCancellable {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        inputDone = true
                    } else {
                        val sampleUs = extractor.sampleTime
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            runMediaCodecCancellable {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            inputDone = true
                        } else {
                            runMediaCodecCancellable {
                                decoder.queueInputBuffer(inIndex, 0, size, remapPtsUs(sampleUs), 0)
                            }
                            lastEncodedSampleUs = sampleUs
                            onFrameEncoded()
                            onProgress(onSamplePts(sampleUs))
                            if (!extractor.advance()) {
                                pendingDecoderEos = true
                            }
                        }
                    }
                }
            }

            // Move decoded frames onto the encoder surface.
            val outIndex = runMediaCodecCancellable { decoder.dequeueOutputBuffer(bufferInfo, timeoutUs) }
            if (outIndex >= 0) {
                val render = bufferInfo.size > 0
                runMediaCodecCancellable { decoder.releaseOutputBuffer(outIndex, render) }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    runMediaCodecCancellable { encoder.signalEndOfInputStream() }
                }
            }

            muxerStarted = drainToMuxer(
                encoder, muxer, bufferInfo,
                onTrackReady = { fmt -> muxerTrack = muxer.addTrack(fmt); muxer.start(); true },
                muxerTrack = { muxerTrack },
                muxerStarted = muxerStarted,
                endOfStream = inputDone,
            ).also { started ->
                if (started && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    decoderDone = true
                }
                // Zero-frame pass (immediate EOS / no muxer track): don't spin until durationUs clears.
                if (inputDone && !started) decoderDone = true
            }

            // Clips with no container duration still need a way out once input is exhausted.
            if (durationUs <= 0L) decoderDone = decoderDone || inputDone
        }
    }

    /**
     * Drain whatever the [encoder] has ready into [muxer]. Returns the (possibly updated)
     * `muxerStarted` flag. Adds the track + starts the muxer lazily on the first
     * `INFO_OUTPUT_FORMAT_CHANGED`.
     */
    private suspend fun drainToMuxer(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        onTrackReady: (MediaFormat) -> Boolean,
        muxerTrack: () -> Int,
        muxerStarted: Boolean,
        endOfStream: Boolean,
        onSampleWritten: () -> Unit = {},
    ): Boolean {
        var started = muxerStarted
        while (true) {
            val outIndex = runMediaCodecCancellable { encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US) }
            when {
                // Nothing ready from the encoder this poll. Before EOS, return so the caller's loop can
                // feed/decode more. At EOS we fall through to the explicit exit just below — the old
                // `else { /* keep draining */ }` was a no-op; draining actually continues across the
                // caller's outer loop, not inside this function.
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return started
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        ReversePreviewLog.d("muxer.format_changed", encoder.outputFormat.toString())
                        started = onTrackReady(encoder.outputFormat)
                    }
                }
                outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && started) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrack(), encoded, bufferInfo)
                        onSampleWritten()
                    }
                    runMediaCodecCancellable { encoder.releaseOutputBuffer(outIndex, false) }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return started
                }
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && endOfStream) {
                // No more output forthcoming at EOS.
                return started
            }
        }
    }

    /**
     * Ask the decoder to tone-map an HDR (HLG/PQ, 10-bit) source down to 8-bit SDR as it decodes onto
     * the encoder's input [Surface]. Without this, an imported HDR clip's 10-bit frames reach the
     * SDR-only AVC encoder, and it fails with "AVC does not support 10-bit input" (codec err 22), which
     * is what wedged the editor on "Loopifying…" for HDR imports.
     *
     * [MediaFormat.KEY_COLOR_TRANSFER_REQUEST] is API 31+ (verified on developer.android.com — added
     * in API 31; see the tone-mapping guide), so this is a no-op below 31 — and a no-op on SDR sources
     * at any level (the decoder only tone-maps actual HDR input). On a device/codec that doesn't honor
     * the request the reverse still fails, but now degrades gracefully (the editor surfaces a retry
     * instead of hanging) rather than spinning forever.
     */
    private fun MediaFormat.requestSdrToneMapping() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    /**
     * Stamp explicit BT709 limited-range SDR metadata on our AVC encoder output. Without this, the
     * muxer can emit `color aspects (0:0:0:0)` and ExoPlayer's preview effects pipeline rejects the
     * reversed half of an imported HDR clip at the forward→reverse seam (`checkColors` /
     * `isDataSpaceValid`).
     */
    private fun MediaFormat.applySdrBt709ColorMetadata() {
        // No SDK guard: these color-metadata keys are API 24+, and minSdk is 26 — the previous
        // `SDK_INT >= N` check was always true (lint ObsoleteSdkInt — PR #58 review).
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }

    /**
     * After [MediaCodec.configure], some encoders expose [MediaCodec.getOutputFormat] before [start]
     * (see MediaCodec API — option B). Returns null when not ready yet.
     */
    private fun readEncoderOutputFormatIfReady(encoder: MediaCodec): MediaFormat? =
        runCatching { encoder.outputFormat }.getOrNull()?.takeIf {
            it.containsKey(MediaFormat.KEY_WIDTH) && it.containsKey(MediaFormat.KEY_HEIGHT)
        }

    /**
     * Best-effort drain for encoders that publish [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] at startup.
     * Many surface encoders (Samsung Exynos RTL) only signal format after the first frame is rendered;
     * pass 1 then relies on [drainToMuxer] (standard decode→surface→encode pattern).
     */
    private fun probeEncoderOutputFormat(encoder: MediaCodec, timeoutNs: Long): MediaFormat? {
        readEncoderOutputFormatIfReady(encoder)?.let { return it }
        val bufferInfo = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + timeoutNs
        while (System.nanoTime() < deadlineNs) {
            when (val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return encoder.outputFormat
                else -> if (outIndex >= 0) encoder.releaseOutputBuffer(outIndex, false)
            }
        }
        return null
    }

    private fun isHardwareAcceleratedEncoder(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name
            !name.contains(".sw.", ignoreCase = true) &&
                !name.contains("software", ignoreCase = true) &&
                !name.contains("google", ignoreCase = true)
        }

    private fun listAvcEncoderTryOrder(format: MediaFormat): List<String> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val avcEncoders = codecList.codecInfos.filter { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME_AVC, ignoreCase = true) } &&
                supportsAvcSurfaceEncode(info, MIME_AVC)
        }
        val installedEncoderNames = avcEncoders.map { it.name }.toSet()
        val formatSupported = avcEncoders.filter { info ->
            runCatching {
                info.getCapabilitiesForType(MIME_AVC).isFormatSupported(format)
            }.getOrDefault(false)
        }.map { it.name }
        val hwByName = avcEncoders.associate { it.name to isHardwareAcceleratedEncoder(it) }
        val samsung = isSamsungDevice()
        val ordered = avcEncoderTryOrderForReverse(
            formatSupportedNames = formatSupported,
            installedEncoderNames = installedEncoderNames,
            isSamsung = samsung,
            isHardwareAccelerated = { name -> hwByName[name] == true },
        )
        val platformPick = runCatching { codecList.findEncoderForFormat(format) }.getOrNull()
        val merged = mergePlatformEncoderPick(
            tryOrder = ordered,
            platformPick = platformPick,
            isSamsung = samsung,
        )
        ReversePreviewLog.d(
            "encoder.try_order",
            "samsung=$samsung strict=${formatSupported.joinToString()} platform=$platformPick → ${merged.joinToString()}",
        )
        return merged
    }

    private data class SurfaceCodecPipeline(
        val decoder: MediaCodec,
        val encoder: MediaCodec,
        val inputSurface: Surface,
    )

    /**
     * Opens a surface-input AVC encoder and a decoder wired to its input [Surface]. Retries once when
     * the surface is invalid or configure fails with "surface has been released" (Pixel RTL /
     * Android 17 (OS) emulator — Crashlytics b09e527). Trying another decoder name with a dead
     * surface never works.
     */
    private fun openSurfaceCodecPipeline(
        encoderFormat: MediaFormat,
        decoderMime: String,
        decoderFormat: MediaFormat,
        preferSoftwareEncoder: Boolean = false,
    ): SurfaceCodecPipeline {
        var lastFailure: Throwable? = null
        repeat(SURFACE_CODEC_PIPELINE_MAX_ATTEMPTS) { attempt ->
            var encoder: MediaCodec? = null
            var surface: Surface? = null
            try {
                val encPair = openSurfaceAvcEncoder(encoderFormat, preferSoftwareEncoder)
                encoder = encPair.first
                surface = encPair.second
                if (!surface.isValid) {
                    releaseEncoderCandidate(encoder, surface)
                    encoder = null
                    surface = null
                    lastFailure = IOException("encoder input surface invalid after createInputSurface")
                    return@repeat
                }
                val decoder = openAvcDecoderForReverse(decoderMime, decoderFormat, surface)
                return SurfaceCodecPipeline(decoder, encoder, surface)
            } catch (t: Throwable) {
                lastFailure = t
                releaseEncoderCandidate(encoder, surface)
                if (
                    attempt < SURFACE_CODEC_PIPELINE_MAX_ATTEMPTS - 1 &&
                        (isMediaCodecSurfaceReleasedFailure(t) || surface?.isValid == false)
                ) {
                    ReversePreviewLog.i(
                        "surface_pipeline.retry",
                        "attempt=${attempt + 2} ${t.javaClass.simpleName}: ${t.message}",
                    )
                    return@repeat
                }
                throw t
            }
        }
        throw lastFailure ?: IllegalStateException("surface codec pipeline failed")
    }

    private fun openSurfaceAvcEncoder(
        format: MediaFormat,
        preferSoftwareEncoder: Boolean = false,
    ): Pair<MediaCodec, Surface> {
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
        var lastFailure: IOException? = null
        val tryOrder = listAvcEncoderTryOrder(format).let { order ->
            if (!preferSoftwareEncoder) return@let order
            // Zero-frame retry (spec §5.8): the S23/API-33 HW encoder's input surface never receives
            // the decoder's pass-2 frames; a software encoder consumes its surface in-process. Front
            // the known SW AVC encoders, keep the rest as fallback.
            val installed = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME_AVC, ignoreCase = true) } }
                .map { it.name }
            val software = listOf("c2.android.avc.encoder", "c2.google.avc.encoder", "OMX.google.h264.encoder")
                .filter { sw -> installed.any { it.equals(sw, ignoreCase = true) } }
            ReversePreviewLog.d("encoder.software_fallback_order", (software + order).distinct().joinToString())
            (software + order).distinct()
        }
        for (name in tryOrder) {
            var encoder: MediaCodec? = null
            var surface: Surface? = null
            try {
                encoder = MediaCodec.createByCodecName(name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val preStartFormat = readEncoderOutputFormatIfReady(encoder)
                surface = encoder.createInputSurface()
                encoder.start()
                val readyFormat = preStartFormat
                    ?: probeEncoderOutputFormat(encoder, ENCODER_OUTPUT_FORMAT_PROBE_TIMEOUT_NS)
                val formatNote = if (readyFormat != null) "output format ready" else "format deferred to pass1 drain"
                ReversePreviewLog.i("encoder.selected", "$name ${w}x$h $formatNote")
                Log.d(TAG, "selectAvcEncoder: $name for ${w}x$h ($formatNote)")
                lastSurfaceEncoderName = name
                return encoder to surface
            } catch (e: IOException) {
                lastFailure = e
                ReversePreviewLog.d("encoder.try_fail", "$name IOException: ${e.message}")
                releaseEncoderCandidate(encoder, surface)
            } catch (e: IllegalArgumentException) {
                lastFailure = IOException("configure failed for $name: ${e.message}", e)
                ReversePreviewLog.d("encoder.try_fail", "$name configure: ${e.message}")
                releaseEncoderCandidate(encoder, surface)
            } catch (e: MediaCodec.CodecException) {
                lastFailure = IOException("codec failed for $name: ${e.message}", e)
                ReversePreviewLog.d("encoder.try_fail", "$name CodecException: ${e.message}")
                releaseEncoderCandidate(encoder, surface)
            }
        }
        return try {
            val encoder = MediaCodec.createEncoderByType(MIME_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val preStartFormat = readEncoderOutputFormatIfReady(encoder)
            val surface = encoder.createInputSurface()
            encoder.start()
            val readyFormat = preStartFormat
                ?: probeEncoderOutputFormat(encoder, ENCODER_OUTPUT_FORMAT_PROBE_TIMEOUT_NS)
            lastSurfaceEncoderName = null
            ReversePreviewLog.i("encoder.selected", "<createEncoderByType> ${w}x$h")
            Log.d(
                TAG,
                "selectAvcEncoder: <default createEncoderByType> for ${w}x$h" +
                    if (readyFormat != null) " (output format ready)" else " (output format deferred)",
            )
            encoder to surface
        } catch (e: IOException) {
            throw lastFailure ?: e
        }
    }

    /**
     * On Samsung, avoid [MediaCodec.createDecoderByType] defaulting to Exynos while ExoPlayer or pass 1
     * still holds codec slots — pair software Google decoder with [openSurfaceAvcEncoder].
     *
     * Note (spec §5.8, on-device 2026-06-04): swapping THIS side to the platform decoder was tried
     * for the S23 zero-frame wedge and failed harder (CodecException 0xe at queueInputBuffer with
     * the HW decoder feeding the HW encoder surface) — the working fallback flips the ENCODER to
     * software instead; the carve-out here stays unconditional.
     */
    private fun openAvcDecoderForReverse(
        mime: String,
        format: MediaFormat,
        outputSurface: Surface,
    ): MediaCodec {
        val installedDecoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { it.name }
            .toSet()
        if (isSamsungDevice()) {
            for (name in samsungSoftwareAvcDecoderTryOrder(installedDecoders)) {
                var decoder: MediaCodec? = null
                try {
                    decoder = MediaCodec.createByCodecName(name)
                    decoder.configure(format, outputSurface, null, 0)
                    decoder.start()
                    ReversePreviewLog.i("decoder.selected", name)
                    Log.d(TAG, "selectAvcDecoder: $name")
                    return decoder
                } catch (e: IOException) {
                    // Narrow to the documented throwables (Lesson 013 / ANDROID_STANDARDS §3), matching
                    // openSurfaceAvcEncoder: try the next decoder on a real codec failure, but let an
                    // IllegalStateException (wrong-state programming error) propagate as a visible bug.
                    onDecoderTryFailed(name, e, decoder)
                } catch (e: IllegalArgumentException) {
                    onDecoderTryFailed(name, e, decoder)
                    // Dead encoder surface — further decoder names cannot succeed (b09e527).
                    if (isMediaCodecSurfaceReleasedFailure(e)) throw e
                } catch (e: MediaCodec.CodecException) {
                    onDecoderTryFailed(name, e, decoder)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime).apply {
            configure(format, outputSurface, null, 0)
            start()
            ReversePreviewLog.i("decoder.selected", "<platform-default> name=$name")
            Log.d(TAG, "selectAvcDecoder: <default createDecoderByType> ($name)")
        }
    }

    /**
     * Tear down a failed encoder probe only. Must not run on a successful [openSurfaceAvcEncoder] return:
     * Kotlin/Java `finally` runs before `return`, which released the live input [Surface] and caused
     * `IllegalArgumentException: The surface has been released` in [openAvcDecoderForReverse] (Pixel RTL).
     */
    private fun releaseEncoderCandidate(encoder: MediaCodec?, surface: Surface?) {
        runCatching { encoder?.stop() }
        surface?.release()
        runCatching { encoder?.release() }
    }

    /** Log a failed decoder candidate and release it so the try-order can move to the next name. */
    private fun onDecoderTryFailed(name: String, error: Exception, decoder: MediaCodec?) {
        ReversePreviewLog.d("decoder.try_fail", "$name ${error.javaClass.simpleName}: ${error.message}")
        runCatching { decoder?.release() }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    private fun estimateBitRate(width: Int, height: Int): Int =
        (width * height * BITS_PER_PIXEL).coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)

    private fun cacheKey(source: File, trimStartMs: Long, trimEndMs: Long): String {
        val raw = "${source.absolutePath}_${trimStartMs}_$trimEndMs"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun logReverseStart(source: File, trimStartMs: Long, trimEndMs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
                val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
                val fps = format.frameRateOrDefault()
                Log.d(
                    TAG,
                    "reverse start: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}, " +
                        "mime=$mime ${w}x$h@${fps}fps, trim=${trimStartMs}..${trimEndMs}ms, " +
                        "bytes=${source.length()}, normalize=${sourceNeedsReverseNormalize(source)}",
                )
                return
            }
            Log.d(TAG, "reverse start: ${Build.MANUFACTURER} ${Build.MODEL}, no video track in ${source.name}")
        } catch (e: Exception) {
            Log.w(TAG, "reverse start: could not probe ${source.name}", e)
        } finally {
            extractor.release()
        }
    }

    companion object {
        private val activeIntermediatePaths = ConcurrentHashMap.newKeySet<String>()

        /**
         * Process-wide memo that this device's HW-encoder surface path produced a zero-frame pass
         * (S23/API 33 wedge): later [reverse] calls start directly on the software encoder instead
         * of re-paying a doomed attempt + [SAMSUNG_CODEC_CONTENTION_RETRY]. Deliberately not
         * persisted — a reboot/codec-update may fix the device, and the cost of rediscovery is one
         * extra attempt.
         */
        @Volatile
        private var zeroFrameEncoderWedgeSticky = false

        /** Paths of in-flight pass-1 intermediates (for [ReverseScratchJanitor] when cancel is wedged). */
        fun trackedIntermediatePaths(): Set<String> = activeIntermediatePaths.toSet()

        const val TAG = "VideoReverser"
        const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DEFAULT_I_FRAME_INTERVAL = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        /** Best-effort pre-frame probe; pass 1 still starts the muxer on [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED]. */
        const val ENCODER_OUTPUT_FORMAT_PROBE_TIMEOUT_NS = 750_000_000L
        const val BITS_PER_PIXEL = 4 // ~4 bits/px → solid quality for the short scratch intermediate
        const val MIN_BIT_RATE = 2_000_000
        const val MAX_BIT_RATE = 24_000_000
        /**
         * Pass 1 re-encodes every fed sample as an I-frame. Library phone exports often carry
         * 60–120 fps metadata with dense samples; capping the feed rate keeps the reverse
         * bounded. NOTE: the export's reversed clips come from this same trim-keyed cache
         * ([Media3VideoProcessor.renderBoomerang] and `ensureReversed` share the reverser), so
         * this cap applies to the SAVED boomerang's reversed half too — only the forward clips
         * are cut from the full-rate source. Sources at or below the cap are never subsampled
         * (see [pass1SampleAction]'s jitter tolerance). KNOWN LIMIT: when subsampling does
         * engage (>30 fps source), pass 1 drops compressed samples before the decoder, which
         * breaks P-frame reference chains and smears moving regions — subsampling should move
         * to render time (decode all, render selectively). Tracked as fold-loop BUG-2.
         */
        const val MAX_PASS1_ENCODE_FPS = 30
        /** Safety valve when the encoder pipeline never signals EOS (device codec wedge). */
        const val MAX_DECODE_LOOP_ITERATIONS = 500_000
        /** Encoder+decoder open retries when the input [Surface] is released before configure (b09e527). */
        const val SURFACE_CODEC_PIPELINE_MAX_ATTEMPTS = 2
    }
}

/** Pure decision for pass-1 frame subsampling (unit-tested). */
internal enum class Pass1SampleAction { ENCODE, SKIP }

/**
 * Whether to encode [sampleUs] in pass 1 or skip it (advance only). Encodes the first sample,
 * any sample at least [minEncodeIntervalUs] after the last encoded one, and the tail of the trim
 * window so the last frame is not dropped.
 */
internal fun pass1SampleAction(
    sampleUs: Long,
    lastEncodedSampleUs: Long,
    endUs: Long,
    minEncodeIntervalUs: Long,
): Pass1SampleAction {
    if (minEncodeIntervalUs <= 0L) return Pass1SampleAction.ENCODE
    if (lastEncodedSampleUs < 0L) return Pass1SampleAction.ENCODE
    // Tolerate timestamp jitter: real "30 fps" sources stamp frames slightly under the nominal
    // interval (e.g. 33,222 µs vs the computed 33,333 µs), so a floor comparison skipped EVERY
    // OTHER frame of an at-cap source — halving the reversed half to ~15 fps. Worse, pass 1
    // skips by dropping compressed samples before the decoder, so the skipped frames' P-frame
    // references are lost and moving regions macroblock-smear (Pixel 6 E2E, 2026-06-04, fold-loop
    // iter 1). A quarter-interval tolerance keeps subsampling engaged only for sources genuinely
    // above the cap (worst-case overshoot: cap × 4/3, e.g. 40 fps for a 120 fps source).
    val jitterToleranceUs = minEncodeIntervalUs / 4
    if (sampleUs - lastEncodedSampleUs >= minEncodeIntervalUs - jitterToleranceUs) {
        return Pass1SampleAction.ENCODE
    }
    if (endUs - sampleUs < minEncodeIntervalUs) return Pass1SampleAction.ENCODE
    return Pass1SampleAction.SKIP
}
