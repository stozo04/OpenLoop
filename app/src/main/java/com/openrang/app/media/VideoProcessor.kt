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
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** How a boomerang loops its source clip. */
enum class BoomerangMode { FORWARD, REVERSE, FORWARD_THEN_REVERSE, REVERSE_THEN_FORWARD }

/**
 * Whether this mode needs the reversed clip generated (everything except a pure [BoomerangMode.FORWARD]).
 * Single source of truth shared by the editor preview, the ViewModel, and the render path.
 */
val BoomerangMode.needsReverse: Boolean get() = this != BoomerangMode.FORWARD

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
        filter: VideoFilter = VideoFilter.ORIGINAL,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File

    /**
     * Produce (or cache-hit) the reversed clip for [source] over `[trimStartMs, trimEndMs]`, returning
     * the reversed [File]. The editor preview calls this so the reversed file is generated ONCE and
     * reused by [renderBoomerang] on save — both go through the same processor's reverser, so they hit
     * the same trim-keyed cache and never reverse the same window twice. Suspends and is cancellable.
     */
    suspend fun ensureReversed(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit = {},
    ): File
}

/**
 * Media3 [Transformer] implementation of [VideoProcessor].
 *
 * The reversed clip of a `*_REVERSE` mode is produced up front by [VideoReverser] (Media3 1.10.x has
 * no reverse effect), so every clip is just a plain forward [EditedMediaItem] — the reversed ones
 * built from the already-reversed file. [boomerangSequence] resolves the clip order and per-clip seam
 * drops; the clips are concatenated in that order into one [EditedMediaItemSequence] and exported with
 * a constant speed effect; audio is stripped.
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
        filter: VideoFilter,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        // The ordered clip plan, with seam-frame drops resolved by sequence POSITION (see
        // boomerangSequence): only a clip that turns direction from the one before it drops its
        // duplicated leading frame — a lone clip and same-direction repeats never drop.
        val specs = boomerangSequence(mode, repetitions)
        val needsReverse = specs.any { it.direction == ClipDirection.REVERSED }
        val reversedFile: File? = if (needsReverse) {
            reverser.reverse(source, trimStartMs, trimEndMs) { frac -> onProgress(frac * REVERSE_BUDGET) }
        } else {
            null
        }
        coroutineContext.ensureActive()
        onProgress(REVERSE_BUDGET)

        // Speed (SpeedChangeEffect) + the chosen color look (RgbFilter / RgbAdjustment / HslAdjustment)
        // compose in one videoEffects list, applied identically to every clip in the sequence.
        val clipEffects = videoEffects(speed, filter)
        // frameDurationMs() does a blocking MediaExtractor header read — keep it off the main thread
        // (renderBoomerang runs on viewModelScope's Main dispatcher; runTransformer hops to Main itself).
        val seamMs = withContext(Dispatchers.IO) { frameDurationMs(source) }
        val items = specs.map { spec ->
            val dropMs = if (spec.dropLeadingFrame) seamMs else 0L
            when (spec.direction) {
                ClipDirection.FORWARD -> forwardItem(source, trimStartMs, trimEndMs, dropMs, clipEffects)
                ClipDirection.REVERSED -> reverseItem(reversedFile!!, dropMs, clipEffects)
            }
        }

        val sequence = EditedMediaItemSequence.withVideoFrom(items)
        val composition = Composition.Builder(sequence).build()

        runTransformer(composition, outputFile) { frac ->
            onProgress(REVERSE_BUDGET + frac * (1f - REVERSE_BUDGET))
        }
        onProgress(1f)
        return outputFile
    }

    override suspend fun ensureReversed(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit,
    ): File = reverser.reverse(source, trimStartMs, trimEndMs, onProgress)

    /** Forward clip over `[startMs, endMs]`; [dropLeadingMs] (>0 at a turn seam) skips its first frame. */
    private fun forwardItem(source: File, startMs: Long, endMs: Long, dropLeadingMs: Long, effects: Effects): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs + dropLeadingMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(source.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    /**
     * Reversed clip; the file already spans only the trim window, so [dropLeadingMs] (>0 at a turn
     * seam) skips its first frame and `0` plays it whole (e.g. a standalone `REVERSE`).
     */
    private fun reverseItem(reversedFile: File, dropLeadingMs: Long, effects: Effects): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(dropLeadingMs)
            .build()
        val item = MediaItem.Builder().setUri(reversedFile.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    private fun videoEffects(speed: Float, filter: VideoFilter): Effects {
        // SpeedChangingVideoEffect does not exist in Media3 1.10.1; the (deprecated) float-constructor
        // SpeedChangeEffect is the only constant-speed video effect — there is no public constant
        // SpeedProvider factory. Verified against the 1.10.1 source tag.
        @Suppress("DEPRECATION")
        val speedEffect: Effect = androidx.media3.effect.SpeedChangeEffect(speed)
        // Speed first, then the color look (order is cosmetic — the look is a per-pixel matrix).
        // ORIGINAL contributes no effects, so a no-filter render is byte-for-byte the slice-04 path.
        return Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(speedEffect) + filter.toMediaEffects(),
        )
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
        } catch (e: IOException) {
            // setDataSource() couldn't read the file — fall back to the 30fps seam offset.
            1000L / DEFAULT_FRAME_RATE
        } catch (e: IllegalArgumentException) {
            // Malformed data source / track format — same safe fallback (never mask a real crash
            // behind a broad catch, ANDROID_STANDARDS §3).
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
