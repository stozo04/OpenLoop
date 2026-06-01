package io.github.stozo04.openloop.work

import java.io.File

/** Terminal outcome of a Loopifying export, observed by the ViewModel. */
sealed interface BoomerangRenderWorkResult {
    data class Success(
        val outputFile: File,
        val returnToGallery: Boolean,
    ) : BoomerangRenderWorkResult

    /** Render failed or was cancelled — partial boomerang output was deleted by the worker. */
    data object Failure : BoomerangRenderWorkResult
}
