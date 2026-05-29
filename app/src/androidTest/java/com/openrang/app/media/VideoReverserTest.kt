package com.openrang.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Instrumented tests for [VideoReverser] — requires a real [MediaCodec], so it cannot run on the JVM.
 *
 * The fixture is obtained in this order:
 *  1. a bundled `app/src/androidTest/assets/trim_fixture.mp4` (drop any short H.264 MP4 there), else
 *  2. a **synthetically generated** luma-ramp clip ([generateLumaRampClip]) — each frame is a solid
 *     gray brighter than the last, so reversal correctness is checkable deterministically (the first
 *     reversed frame must be the brightest = the last source frame).
 *
 * If neither is available (e.g. a device whose encoder rejects the synthetic format), the tests SKIP
 * via [assumeTrue] rather than fail — an honest "not exercised here," never a false green.
 */
@RunWith(AndroidJUnit4::class)
class VideoReverserTest {

    private lateinit var scratchDir: File
    private lateinit var reverser: VideoReverser
    private lateinit var fixture: File
    private var durationMs: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scratchDir = File(context.cacheDir, "test_reversed_${System.nanoTime()}").apply { mkdirs() }
        reverser = VideoReverser(scratchDir)
        fixture = obtainFixtureOrSkip(context)
        durationMs = MediaMetadataRetriever().use { r ->
            r.setDataSource(fixture.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        assumeTrue("fixture has no readable duration", durationMs > 0L)
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    @Test
    fun reverse_producesOutput_andLeavesNoIntermediate() = runBlocking {
        val output = reverser.reverse(fixture, 0L, durationMs)

        assertTrue("reversed output should exist", output.exists())
        assertTrue("reversed output should be non-empty", output.length() > 0L)
        // The keyframe-only intermediate must be deleted regardless of outcome.
        assertTrue(
            "no _intermediate_ files should remain",
            scratchDir.listFiles()?.none { it.name.startsWith("_intermediate_") } ?: true,
        )
    }

    @Test
    fun reverse_durationMatchesTrimWindow_withinOneFrame() = runBlocking {
        val output = reverser.reverse(fixture, 0L, durationMs)

        val outMs = MediaMetadataRetriever().use { r ->
            r.setDataSource(output.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        // Allow ~2 frames (≈66 ms @30fps) of codec round-trip slack.
        assertTrue("reversed duration $outMs ≉ source $durationMs", abs(outMs - durationMs) <= 70L)
    }

    @Test
    fun reverse_isCached_onIdenticalSourceAndTrim() = runBlocking {
        val first = reverser.reverse(fixture, 0L, durationMs)
        val firstModified = first.lastModified()

        val second = reverser.reverse(fixture, 0L, durationMs)

        assertEquals("identical request should return the cached path", first.absolutePath, second.absolutePath)
        assertEquals("cache hit must not re-encode", firstModified, second.lastModified())
    }

    /**
     * The actual correctness guarantee: the **first** frame of the reversed output is the **last**
     * frame of the source trim window. Compared by mean luma (exact pixel equality is unreliable
     * across an encode round-trip), with a tolerance that comfortably absorbs codec drift while still
     * failing a non-reversed (or reversed-the-wrong-way) output.
     */
    @Test
    fun reverse_firstFrameApproximatesLastSourceFrame() = runBlocking {
        val output = reverser.reverse(fixture, 0L, durationMs)

        val sourceLast = frameLuma(fixture, (durationMs - 1).coerceAtLeast(0L) * 1000L)
        val reversedFirst = frameLuma(output, 0L)
        assumeTrue("could not decode comparison frames", sourceLast >= 0 && reversedFirst >= 0)

        val delta = abs(sourceLast - reversedFirst)
        assertTrue(
            "reversed first-frame luma ($reversedFirst) should match source last-frame luma " +
                "($sourceLast) within tolerance; delta=$delta",
            delta <= LUMA_TOLERANCE,
        )

        // Sanity guard against a degenerate all-one-color fixture making the above vacuous: for the
        // synthetic ramp the FIRST source frame is clearly darker than the last, so a correct reverse
        // must put the bright frame first. (Skipped automatically for a flat bundled clip.)
        val sourceFirst = frameLuma(fixture, 0L)
        if (sourceFirst >= 0 && abs(sourceFirst - sourceLast) > RAMP_MIN_SPREAD) {
            assertTrue(
                "reverse appears not to have flipped frame order (reversedFirst=$reversedFirst, " +
                    "sourceFirst=$sourceFirst, sourceLast=$sourceLast)",
                abs(reversedFirst - sourceLast) < abs(reversedFirst - sourceFirst),
            )
        }
    }

    // ── Fixture acquisition ──────────────────────────────────────────────────────────────────────

    /** Bundled asset → synthetic generation → skip. Never returns a non-existent file. */
    private fun obtainFixtureOrSkip(context: Context): File {
        val dest = File(context.cacheDir, "trim_fixture.mp4")
        // 1. Prefer a bundled asset.
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        try {
            testAssets.open("trim_fixture.mp4").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() > 0L) return dest
        } catch (e: IOException) {
            // No bundled asset — fall through to synthetic generation.
        }
        // 2. Generate a synthetic luma-ramp clip.
        val generated = File(context.cacheDir, "synthetic_fixture_${System.nanoTime()}.mp4")
        val ok = runCatching { generateLumaRampClip(generated) }.getOrDefault(false)
        assumeTrue("no bundled fixture and synthetic generation unavailable on this device", ok)
        return generated
    }

    /**
     * Encode [frameCount] solid-gray frames whose luma climbs from dark→bright into an H.264 MP4 at
     * [dest], using a ByteBuffer (Image) input path. Returns `true` on success. Any codec/muxer
     * failure returns `false` so the caller can skip rather than fail.
     */
    private fun generateLumaRampClip(
        dest: File,
        frameCount: Int = 12,
        fps: Int = 12,
        width: Int = 320,
        height: Int = 240,
    ): Boolean {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            val frameDurUs = 1_000_000L / fps
            var muxerTrack = -1
            var muxerStarted = false
            var frameIndex = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex >= frameCount) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, frameIndex * frameDurUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            // Ramp luma across the valid 16..235 video range. Multiply BEFORE
                            // dividing — `frameIndex * (219 / frameCount)` is integer division and
                            // collapses the step to 0 (a vacuous all-one-color clip) once frameCount
                            // exceeds 219; `(frameIndex * 219) / frameCount` keeps a real ramp.
                            val luma = (16 + (frameIndex * 219) / frameCount).coerceIn(16, 235)
                            val image = codec.getInputImage(inIndex)
                                ?: return false // device isn't giving us a flexible image — skip
                            fillGray(image, luma)
                            codec.queueInputBuffer(
                                inIndex, 0, width * height * 3 / 2, frameIndex * frameDurUs, 0,
                            )
                            frameIndex++
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            dest.length() > 0L
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    /** Fill an [Image]'s Y plane with [luma] and the chroma planes with neutral 128 (grayscale). */
    private fun fillGray(image: Image, luma: Int) {
        val w = image.width
        val h = image.height
        val planes = image.planes

        val y = planes[0]
        val yBuf = y.buffer
        for (row in 0 until h) {
            var idx = row * y.rowStride
            var col = 0
            while (col < w) {
                yBuf.put(idx, luma.toByte())
                idx += y.pixelStride
                col++
            }
        }

        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        for (p in 1..2) {
            val plane = planes[p]
            val buf = plane.buffer
            for (row in 0 until ch) {
                var idx = row * plane.rowStride
                var col = 0
                while (col < cw) {
                    buf.put(idx, 128.toByte())
                    idx += plane.pixelStride
                    col++
                }
            }
        }
    }

    /** Mean luma (0..255) of the frame nearest [timeUs], or -1 if it can't be decoded. */
    private fun frameLuma(file: File, timeUs: Long): Int {
        val bitmap: Bitmap = MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return -1

        var total = 0L
        var count = 0
        val stepX = (bitmap.width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_GRID).coerceAtLeast(1)
        var x = 0
        while (x < bitmap.width) {
            var yy = 0
            while (yy < bitmap.height) {
                val px = bitmap.getPixel(x, yy)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                total += (r + g + b) / 3
                count++
                yy += stepY
            }
            x += stepX
        }
        bitmap.recycle()
        return if (count == 0) -1 else (total / count).toInt()
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val LUMA_TOLERANCE = 45      // codec round-trip slack on mean luma (0..255)
        const val RAMP_MIN_SPREAD = 20     // first vs last must differ this much to apply the order check
        const val SAMPLE_GRID = 16         // sample ~16×16 pixels for the mean
    }
}
