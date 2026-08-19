package io.github.stozo04.openloop.work

import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.VideoFilter
import java.io.File

/**
 * Parameters for a one-shot Loopifying export. The ViewModel builds this after promoting scratch →
 * raw and allocating the boomerang output path; the scheduler enqueues it as WorkManager [Data].
 */
data class BoomerangRenderRequest(
    val scratch: ScratchCapture,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val mode: BoomerangMode,
    val speed: Float,
    val curve: SpeedCurve? = null,
    val filter: VideoFilter,
    val repetitions: Int,
    val rawId: Long,
    val outputFile: File,
    val returnToGallery: Boolean,
) {
    /** Unique work name — one in-flight render per scratch UUID (duplicate Save taps are ignored). */
    val uniqueWorkName: String get() = "render_${scratch.uuid}"

    /**
     * The speed the renderer actually applies: the user's curve, or [speed] as a flat curve when the
     * editor was in Constant mode. One speed path downstream (PRD-speed-curves.md D-2).
     */
    val effectiveCurve: SpeedCurve get() = curve ?: SpeedCurve.flat(speed)
}
