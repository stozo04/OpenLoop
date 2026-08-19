package io.github.stozo04.openloop.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.IOException
import java.util.TreeMap
import kotlin.math.roundToLong

/**
 * A [SpeedProvider] backed by sampled keyframes: `start time (µs) → speed`, holding each speed until
 * the next entry.
 *
 * Media3 1.10.1 ships no public keyframe provider — its own `SegmentSpeedProvider` (which reads
 * Samsung slow-motion metadata) is package-private, so this mirrors its shape rather than reusing it:
 * a sorted map queried with `floorEntry` for the current speed and `higherKey` for the next change.
 *
 * Handed to `EditedMediaItem.Builder.setSpeed`, Transformer wraps the item's `MediaSource` in a
 * `SpeedChangingMediaSource`, which re-stamps `buffer.timeUs` on every sample **before the decoder**.
 * That path is track-agnostic (so video is covered), cheaper than the deprecated `SpeedChangeEffect`
 * shader stage (no GL pass), and clipping-aware — which is what makes it usable here at all, since
 * every clip OpenLoop builds carries a `ClippingConfiguration` including the seam-frame offsets.
 *
 * Timestamps are clamped rather than asserted: Media3's own provider does `checkArgument(timeUs >= 0)`,
 * but an exception thrown from inside the sample-read path would take a user's save down. A negative
 * query simply reads the first step.
 */
@UnstableApi
class KeyframeSpeedProvider(steps: Map<Long, Float>) : SpeedProvider {

    private val speedsByStartTimeUs = TreeMap<Long, Float>().apply {
        steps.forEach { (timeUs, speed) -> put(timeUs.coerceAtLeast(0L), SpeedCurve.clampSpeed(speed)) }
        // A provider with no entries would report 1× forever and silently ignore the user's curve.
        if (isEmpty()) put(0L, SpeedCurve.NEUTRAL_SPEED)
    }

    override fun getSpeed(timeUs: Long): Float {
        val safeTimeUs = timeUs.coerceAtLeast(0L)
        val entry = speedsByStartTimeUs.floorEntry(safeTimeUs)
            ?: speedsByStartTimeUs.firstEntry()
        return entry?.value ?: SpeedCurve.NEUTRAL_SPEED
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        val safeTimeUs = timeUs.coerceAtLeast(0L)
        return speedsByStartTimeUs.higherKey(safeTimeUs) ?: C.TIME_UNSET
    }

    /** True when this provider is a single constant speed — lets the render skip the wrapper. */
    internal val isConstant: Boolean get() = speedsByStartTimeUs.size == 1
}

/**
 * One clip's span on the loop's **1× input** timeline: where it starts and how long it runs.
 *
 * "Input timeline" means before any speed is applied — that is the domain a [SpeedCurve] is defined
 * over, and the only one where the mapping stays fixed while the user drags the curve.
 */
internal data class ClipSpan(val startUs: Long, val durationUs: Long)

/**
 * Lay the clip sequence out on the loop's 1× timeline.
 *
 * **This is the highest-risk arithmetic in the speed-curve feature.** Each clip's start must
 * accumulate from the **post-seam-drop** durations of the clips before it, never from
 * `index × windowUs`. A clip that turns direction from its predecessor drops its leading frame and
 * is therefore one frame *shorter* (Lesson 018 — the drop follows sequence position, not clip
 * identity). Using the nominal window length instead would slide the curve later by one frame per
 * turn: invisible on a two-clip loop, obviously wrong once repetitions stack turns up.
 *
 * The reversed clip's length is **measured, not assumed**. It is normally within a frame of the trim
 * window, but it is a separately encoded artifact and nothing guarantees that — a low-frame-rate or
 * otherwise degenerate source can produce a reversed clip materially shorter than its window.
 * Assuming `windowMs` for it laid the reverse half of the curve over footage that was not there, and
 * the rendered length then disagreed with the duration the editor had promised the user. Callers with
 * no reversed clip (a pure-forward loop, or before one has been generated) pass `null` and get the
 * window as the estimate.
 *
 * @param specs the resolved clip plan from [boomerangSequence].
 * @param windowMs the trim window's length — the length of every forward clip.
 * @param seamMs one source frame, the amount a turning clip drops.
 * @param reversedMs measured duration of the reversed clip, or null to fall back to [windowMs].
 */
internal fun loopClipSpans(
    specs: List<ClipSpec>,
    windowMs: Long,
    seamMs: Long,
    reversedMs: Long? = null,
): List<ClipSpan> {
    val windowUs = (windowMs.coerceAtLeast(0L)) * 1_000L
    val reversedUs = (reversedMs?.takeIf { it > 0L } ?: windowMs).coerceAtLeast(0L) * 1_000L
    val seamUs = (seamMs.coerceAtLeast(0L)) * 1_000L
    var cursorUs = 0L
    return specs.map { spec ->
        val dropUs = if (spec.dropLeadingFrame) seamUs else 0L
        val nominalUs = when (spec.direction) {
            ClipDirection.FORWARD -> windowUs
            ClipDirection.REVERSED -> reversedUs
        }
        val durationUs = (nominalUs - dropUs).coerceAtLeast(0L)
        ClipSpan(startUs = cursorUs, durationUs = durationUs).also { cursorUs += durationUs }
    }
}

/**
 * Duration of [source]'s video track in milliseconds, or 0 when unreadable.
 *
 * Same blocking-`MediaExtractor` shape as [sourceFrameRateFromFile], and here for the same reason:
 * the render needs the reversed clip's *measured* length to lay the speed curve over it, and a header
 * read is the cheapest honest way to get one.
 */
internal fun videoDurationMsOf(source: File): Long {
    if (!source.exists() || source.length() <= 0L) return 0L
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(source.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                format.containsKey(MediaFormat.KEY_DURATION)
            ) {
                return format.getLong(MediaFormat.KEY_DURATION) / 1_000L
            }
        }
        0L
    } catch (e: IOException) {
        0L
    } catch (e: IllegalArgumentException) {
        0L
    } finally {
        extractor.release()
    }
}

/** Total length of the laid-out loop on the 1× input timeline. */
internal fun List<ClipSpan>.totalUs(): Long = sumOf { it.durationUs }

/**
 * How long the rendered loop will actually be, given the clip layout and an equivalent constant
 * [speed] (for a curve, that is [SpeedCurve.flatten] — the harmonic mean, which is *defined* as the
 * constant producing the same output length).
 *
 * The editor's duration chip reads from this rather than from clip-count × window arithmetic, so the
 * number shown to the user is derived from the very same spans the render slices the curve over. When
 * the two were computed separately the chip could promise a length the encoder never produced.
 */
internal fun loopOutputDurationMs(spans: List<ClipSpan>, speed: Float): Long {
    val inputMs = spans.totalUs() / 1_000L
    val safeSpeed = if (speed > 0f) speed else 1f
    return (inputMs.toDouble() / safeSpeed).roundToLong()
}

/**
 * Build the [SpeedProvider] for one clip: the slice of [curve] covering [span], rebased to
 * clip-local time (Media3 hands each `EditedMediaItem` its own zero-based timeline).
 *
 * @param stepUs sampling granularity — pass one source frame.
 */
@UnstableApi
internal fun speedProviderForClip(
    curve: SpeedCurve,
    span: ClipSpan,
    totalUs: Long,
    stepUs: Long,
): KeyframeSpeedProvider =
    KeyframeSpeedProvider(
        curve.sampleClip(
            clipStartUs = span.startUs,
            clipDurationUs = span.durationUs,
            totalUs = totalUs,
            stepUs = stepUs,
        )
    )
