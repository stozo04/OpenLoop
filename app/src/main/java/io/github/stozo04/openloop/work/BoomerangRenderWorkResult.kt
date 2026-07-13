package io.github.stozo04.openloop.work

import java.io.File

/** Terminal outcome of a Loopifying export, observed by the ViewModel. */
sealed interface BoomerangRenderWorkResult {
    data class Success(
        val outputFile: File,
        val returnToGallery: Boolean,
    ) : BoomerangRenderWorkResult

    /**
     * Render failed or was cancelled — partial boomerang output was deleted by the worker.
     *
     * @param reason the worker's failure outcome (e.g. `render_failed_export: ExportException: …`),
     *   carried across WorkManager via the failure `Data` (Crashlytics 47233ad7 triage — the bare
     *   object told the observer nothing). Null when the work failed without attaching one
     *   (process death, work persisted by an older app version).
     * @param workerReportedCause true when the worker already recorded the genuine exception to
     *   Crashlytics — the observer must not report a second, synthetic-cause non-fatal for it.
     */
    data class Failure(
        val reason: String? = null,
        val workerReportedCause: Boolean = false,
    ) : BoomerangRenderWorkResult
}
