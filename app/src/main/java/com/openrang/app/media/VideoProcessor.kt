package com.openrang.app.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** How a boomerang loops its source clip. */
enum class BoomerangMode { FORWARD, REVERSE, FORWARD_THEN_REVERSE, REVERSE_THEN_FORWARD }

/**
 * Renders a boomerang MP4 from a trimmed source clip.
 *
 * Slice 02 drives this with a single hard-wired config (`FORWARD_THEN_REVERSE`, `speed = 2.0×`,
 * `repetitions = 1`); the full parameter surface exists so slices 03–05 (direction / speed / reps
 * tabs) can vary it without changing the contract.
 */
interface VideoProcessor {
    /**
     * Render a boomerang of [source] over `[trimStartMs, trimEndMs]` to [outputFile], returning it.
     * Suspending and cancellable; reports `0f..1f` via [onProgress]. Throws on a render failure
     * (the caller maps that to the user-facing "couldn't save" path).
     */
    suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File
}

/**
 * Media3 [Transformer] implementation of [VideoProcessor].
 *
 * The reversed half of a `*_REVERSE` mode is produced up front by [VideoReverser] (Media3 1.10.x has
 * no reverse effect), so each half is just a plain forward [EditedMediaItem] — the reversed one built
 * from the already-reversed file. The two halves are concatenated in a single
 * [EditedMediaItemSequence] and exported with a constant 2× speed effect; audio is stripped.
 *
 * [Context] is injected via the ViewModel `Factory` (never passed to a ViewModel method — Lesson 004).
 *
 * Progress budget: the reverse pass owns `0f..0.8f`, the Composition encode owns `0.8f..1f`.
 */
@UnstableApi
class Media3VideoProcessor(
    private val context: Context,
    private val reverser: VideoReverser,
) : VideoProcessor {

    override suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        val needsReverse = mode != BoomerangMode.FORWARD
        val reversedFile: File? = if (needsReverse) {
            reverser.reverse(source, trimStartMs, trimEndMs) { frac -> onProgress(frac * REVERSE_BUDGET) }
        } else {
            null
        }
        coroutineContext.ensureActive()
        onProgress(REVERSE_BUDGET)

        val speedEffects = speedEffects(speed)
        val forward = forwardItem(source, trimStartMs, trimEndMs, speedEffects)
        // Skip the reversed half's first frame: it duplicates the forward half's last frame at the seam
        // (parent IMPLEMENTATION.md §6.4). Offset by one source frame's duration.
        val reversed = reversedFile?.let { reverseItem(it, frameDurationMs(source), speedEffects) }

        val ordered = when (mode) {
            BoomerangMode.FORWARD -> listOf(forward)
            BoomerangMode.REVERSE -> listOf(reversed!!)
            BoomerangMode.FORWARD_THEN_REVERSE -> listOf(forward, reversed!!)
            BoomerangMode.REVERSE_THEN_FORWARD -> listOf(reversed!!, forward)
        }
        val items = (0 until repetitions.coerceAtLeast(1)).flatMap { ordered }

        val sequence = EditedMediaItemSequence.withVideoFrom(items)
        val composition = Composition.Builder(sequence).build()

        runTransformer(composition, outputFile) { frac ->
            onProgress(REVERSE_BUDGET + frac * (1f - REVERSE_BUDGET))
        }
        onProgress(1f)
        return outputFile
    }

    private fun forwardItem(source: File, startMs: Long, endMs: Long, effects: Effects): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(source.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    private fun reverseItem(reversedFile: File, seamOffsetMs: Long, effects: Effects): EditedMediaItem {
        // The reversed file already spans only the trim window, so just trim the 1-frame seam off its head.
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(seamOffsetMs)
            .build()
        val item = MediaItem.Builder().setUri(reversedFile.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    private fun speedEffects(speed: Float): Effects {
        // SpeedChangingVideoEffect does not exist in Media3 1.10.1; the (deprecated) float-constructor
        // SpeedChangeEffect is the only constant-speed video effect — there is no public constant
        // SpeedProvider factory. Verified against the 1.10.1 source tag.
        @Suppress("DEPRECATION")
        val speedEffect: Effect = androidx.media3.effect.SpeedChangeEffect(speed)
        return Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ listOf(speedEffect))
    }

    /** Run [composition] → [outputFile], bridging the async [Transformer] callbacks into a suspend call. */
    private suspend fun runTransformer(
        composition: Composition,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        // Transformer requires a Looper thread; build/start/poll/cancel all happen on Main.
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        coroutineScope {
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            try {
                transformer.start(composition, outputFile.absolutePath)
                done.await()
            } finally {
                poller.cancel()
                transformer.cancel() // safe after completion; aborts the export if we were canceled mid-flight
            }
        }
    }

    /** One source frame's duration in ms (for the seam offset), from the track frame rate; 30fps fallback. */
    private fun frameDurationMs(source: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            var fps = DEFAULT_FRAME_RATE
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    // frameRateOrDefault() is type-tolerant: KEY_FRAME_RATE is sometimes a Float, and
                    // getInteger() would throw ClassCastException (which the broad catch below would
                    // then mask by defaulting the WHOLE function). Reuse the shared, unit-tested util.
                    fps = format.frameRateOrDefault()
                    break
                }
            }
            (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        } catch (e: Exception) {
            1000L / DEFAULT_FRAME_RATE
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val REVERSE_BUDGET = 0.8f
        const val PROGRESS_POLL_MS = 100L
        const val DEFAULT_FRAME_RATE = 30
    }
}
