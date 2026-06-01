package io.github.stozo04.openloop.media

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File

/**
 * Shared synthetic H.264 fixtures for instrumented media tests. Returns `false` / `-1` on codec failure
 * so callers can [org.junit.Assume.assumeTrue] skip rather than false-green.
 */
internal object SyntheticVideoFixtures {

    const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Mean luma (0..255) of the frame nearest [timeUs], or -1 if it can't be decoded. */
    fun frameLuma(file: File, timeUs: Long, sampleGrid: Int = 16): Int {
        val bitmap = MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return -1

        var total = 0L
        var count = 0
        val stepX = (bitmap.width / sampleGrid).coerceAtLeast(1)
        val stepY = (bitmap.height / sampleGrid).coerceAtLeast(1)
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

    fun videoShortSide(file: File): Int {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (w > 0 && h > 0) return minOf(w, h)
                }
            }
            0
        } finally {
            extractor.release()
        }
    }

    fun durationMs(file: File): Long =
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }

    /**
     * Encode solid-gray luma-ramp frames into an H.264 MP4 at [dest]. Each frame is brighter than the
     * last so reversal correctness is checkable deterministically.
     */
    fun generateLumaRampClip(
        dest: File,
        width: Int,
        height: Int,
        frameCount: Int = 12,
        fps: Int = 12,
    ): Boolean {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
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
                            val luma = (16 + (frameIndex * 219) / frameCount).coerceIn(16, 235)
                            val image = codec.getInputImage(inIndex) ?: return false
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
}
