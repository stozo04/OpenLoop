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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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
        if (output.exists() && output.length() > 0L) {
            onProgress(1f)
            return@withContext output
        }

        val intermediate = File(scratchDir, "_intermediate_${UUID.randomUUID()}.mp4")
        logReverseStart(source, trimStartMs, trimEndMs)
        try {
            transcodeToAllKeyframes(source, trimStartMs, trimEndMs, intermediate) { frac ->
                onProgress(frac * 0.5f) // pass 1 occupies the first half of the progress budget
            }
            coroutineContext.ensureActive()
            reverseAllKeyframeVideo(intermediate, output) { frac ->
                onProgress(0.5f + frac * 0.5f) // pass 2 occupies the second half
            }
            onProgress(1f)
            output
        } catch (t: Throwable) {
            // Don't leave a half-written reversed file behind on any failure (incl. cancellation).
            output.delete()
            throw t
        } finally {
            intermediate.delete()
        }
    }

    // ── Pass 1: trim + re-encode so every frame is an I-frame (seekable per-frame) ──────────────

    private suspend fun transcodeToAllKeyframes(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        dest: File,
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

            encoder = selectAvcEncoder(encoderFormat).apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inputFormat, inputSurface, null, 0)
                start()
            }

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
                encoder = encoder,
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
            Log.d(
                TAG,
                "reverse pass1: ${width}x$height, encoded=$encodedFrames, skipped=$skippedFrames, fpsCap=$frameRate",
            )
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
            encoder = selectAvcEncoder(encoderFormat).apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inputFormat, inputSurface, null, 0)
                start()
            }
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

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (feedIndex < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleUs = frameTimesUs[feedIndex]
                            extractor.seekTo(sampleUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val buffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, endUs - sampleUs, 0)
                            }
                            feedIndex--
                        }
                    }
                }

                // Render each decoded frame onto the encoder surface (carrying its reversed PTS).
                val outIndex = decoder.dequeueOutputBuffer(decoderInfo, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    val render = decoderInfo.size > 0
                    decoder.releaseOutputBuffer(outIndex, render)
                    if (render) {
                        emitted++
                        onProgress((emitted.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                    }
                }

                muxerStarted = drainToMuxer(
                    encoder, muxer, encoderInfo,
                    onTrackReady = { fmt -> muxerTrack = muxer.addTrack(fmt); muxer.start(); true },
                    muxerTrack = { muxerTrack },
                    muxerStarted = muxerStarted,
                    endOfStream = inputDone,
                ).also { if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && it) outputDone = true }
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

                val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    if (pendingDecoderEos) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val sampleUs = extractor.sampleTime
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, remapPtsUs(sampleUs), 0)
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
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                val render = bufferInfo.size > 0
                decoder.releaseOutputBuffer(outIndex, render)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoder.signalEndOfInputStream()
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
    private fun drainToMuxer(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        onTrackReady: (MediaFormat) -> Boolean,
        muxerTrack: () -> Int,
        muxerStarted: Boolean,
        endOfStream: Boolean,
    ): Boolean {
        var started = muxerStarted
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                // Nothing ready from the encoder this poll. Before EOS, return so the caller's loop can
                // feed/decode more. At EOS we fall through to the explicit exit just below — the old
                // `else { /* keep draining */ }` was a no-op; draining actually continues across the
                // caller's outer loop, not inside this function.
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return started
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) started = onTrackReady(encoder.outputFormat)
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
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    /**
     * Create an AVC encoder for [format], preferring a hardware-accelerated one that supports the
     * format. On some devices `createEncoderByType` defaults to the *software* encoder
     * (`c2.google.avc.encoder`), which encodes a high-res / high-fps clip frame-by-frame so slowly the
     * render appears to hang. Picking a hardware encoder makes a 4K/60 import finish in seconds.
     * Falls back to the platform default for the type if no supporting hardware encoder is found.
     */
    private fun selectAvcEncoder(format: MediaFormat): MediaCodec {
        val name = runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val supporting = infos.filter { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MIME_AVC, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(MIME_AVC).isFormatSupported(format)
                    }.getOrDefault(false)
            }
            supporting.minByOrNull { encoderPreferenceRank(it, format) }?.name
        }.getOrNull()
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
        Log.d(TAG, "selectAvcEncoder: ${name ?: "<default createEncoderByType>"} for ${w}x$h")
        return if (name != null) MediaCodec.createByCodecName(name)
        else MediaCodec.createEncoderByType(MIME_AVC)
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

    /** Lower rank = preferred. Deprioritize Google's software AVC (Lesson 020); prefer vendor HW. */
    private fun encoderPreferenceRank(info: MediaCodecInfo, format: MediaFormat): Int {
        var rank = 0
        val name = info.name

        // Check if format requests "all I-frames" (KEY_I_FRAME_INTERVAL = 0)
        val isAllKeyframes = format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) &&
            format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL) == 0

        if (isAllKeyframes && isSamsungDevice()) {
            val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !name.contains(".sw.", ignoreCase = true) &&
                    !name.contains("software", ignoreCase = true) &&
                    !name.contains("google", ignoreCase = true)
            }
            if (isHardware) {
                // Samsung hardware encoders (Exynos/SEC) are known to hang or wedge when KEY_I_FRAME_INTERVAL = 0 is requested.
                // Heavily deprioritize them to force software encoder fallback for all-keyframe pass.
                rank += 500
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !info.isHardwareAccelerated) {
            rank += 100
        }
        if (name.contains("google", ignoreCase = true)) rank += 50
        if (name.contains(".sw.", ignoreCase = true) || name.contains("software", ignoreCase = true)) {
            rank += 100
        }
        // Samsung Exynos / SEC and Qualcomm encoders are typically much faster than c2.google.avc.encoder.
        if (
            name.contains("exynos", ignoreCase = true) ||
                name.contains("c2.sec", ignoreCase = true) ||
                name.contains("qcom", ignoreCase = true) ||
                name.contains("qti", ignoreCase = true)
        ) {
            rank -= 25
        }
        return rank
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

    private companion object {
        const val TAG = "VideoReverser"
        const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DEFAULT_I_FRAME_INTERVAL = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val BITS_PER_PIXEL = 4 // ~4 bits/px → solid quality for the short scratch intermediate
        const val MIN_BIT_RATE = 2_000_000
        const val MAX_BIT_RATE = 24_000_000
        /**
         * Pass 1 re-encodes every fed sample as an I-frame. Library phone exports often carry
         * 60–120 fps metadata with dense samples; capping the feed rate keeps preview reverse
         * bounded without changing export (still uses full-rate source at render time).
         */
        const val MAX_PASS1_ENCODE_FPS = 30
        /** Safety valve when the encoder pipeline never signals EOS (device codec wedge). */
        const val MAX_DECODE_LOOP_ITERATIONS = 500_000
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
    if (sampleUs - lastEncodedSampleUs >= minEncodeIntervalUs) return Pass1SampleAction.ENCODE
    if (endUs - sampleUs < minEncodeIntervalUs) return Pass1SampleAction.ENCODE
    return Pass1SampleAction.SKIP
}
