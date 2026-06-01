package io.github.stozo04.openloop.work

import androidx.work.Data
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import java.io.File

/**
 * WorkManager [Data] keys for [BoomerangRenderWorker]. Paths are strings — never parcel [File]
 * objects across process boundaries.
 */
object BoomerangRenderWorkerKeys {
    const val SCRATCH_PATH = "scratch_path"
    const val SCRATCH_UUID = "scratch_uuid"
    const val TRIM_START_MS = "trim_start_ms"
    const val TRIM_END_MS = "trim_end_ms"
    const val MODE = "mode"
    const val SPEED = "speed"
    const val FILTER = "filter"
    const val REPETITIONS = "repetitions"
    const val RAW_ID = "raw_id"
    const val OUTPUT_PATH = "output_path"
    const val RETURN_TO_GALLERY = "return_to_gallery"

    /** Worker → UI progress (0..100). */
    const val PROGRESS_PERCENT = "progress_percent"

    /** Success output — absolute path to the registered boomerang MP4. */
    const val OUTPUT_FILE_PATH = "output_file_path"
}

/** Maps [BoomerangRenderRequest] ↔ WorkManager [Data]. JVM-unit-testable. */
object BoomerangRenderWorkerInput {

    fun toInputData(request: BoomerangRenderRequest): Data =
        Data.Builder()
            .putString(BoomerangRenderWorkerKeys.SCRATCH_PATH, request.scratch.file.absolutePath)
            .putString(BoomerangRenderWorkerKeys.SCRATCH_UUID, request.scratch.uuid)
            .putLong(BoomerangRenderWorkerKeys.TRIM_START_MS, request.trimStartMs)
            .putLong(BoomerangRenderWorkerKeys.TRIM_END_MS, request.trimEndMs)
            .putString(BoomerangRenderWorkerKeys.MODE, request.mode.name)
            .putFloat(BoomerangRenderWorkerKeys.SPEED, request.speed)
            .putString(BoomerangRenderWorkerKeys.FILTER, request.filter.name)
            .putInt(BoomerangRenderWorkerKeys.REPETITIONS, request.repetitions)
            .putLong(BoomerangRenderWorkerKeys.RAW_ID, request.rawId)
            .putString(BoomerangRenderWorkerKeys.OUTPUT_PATH, request.outputFile.absolutePath)
            .putBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, request.returnToGallery)
            .build()

    fun from(data: Data): Parsed? {
        val scratchPath = data.getString(BoomerangRenderWorkerKeys.SCRATCH_PATH) ?: return null
        val scratchUuid = data.getString(BoomerangRenderWorkerKeys.SCRATCH_UUID) ?: return null
        val modeName = data.getString(BoomerangRenderWorkerKeys.MODE) ?: return null
        val filterName = data.getString(BoomerangRenderWorkerKeys.FILTER) ?: return null
        val outputPath = data.getString(BoomerangRenderWorkerKeys.OUTPUT_PATH) ?: return null
        val mode = runCatching { BoomerangMode.valueOf(modeName) }.getOrNull() ?: return null
        val filter = runCatching { VideoFilter.valueOf(filterName) }.getOrNull() ?: return null
        return Parsed(
            scratch = ScratchCapture(scratchUuid, File(scratchPath)),
            trimStartMs = data.getLong(BoomerangRenderWorkerKeys.TRIM_START_MS, -1L),
            trimEndMs = data.getLong(BoomerangRenderWorkerKeys.TRIM_END_MS, -1L),
            mode = mode,
            speed = data.getFloat(BoomerangRenderWorkerKeys.SPEED, Float.NaN),
            filter = filter,
            repetitions = data.getInt(BoomerangRenderWorkerKeys.REPETITIONS, -1),
            rawId = data.getLong(BoomerangRenderWorkerKeys.RAW_ID, -1L),
            outputFile = File(outputPath),
            returnToGallery = data.getBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, false),
        ).takeUnless { it.trimStartMs < 0 || it.trimEndMs < 0 || it.repetitions < 0 || it.rawId < 0 || it.speed.isNaN() }
    }

    data class Parsed(
        val scratch: ScratchCapture,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val mode: BoomerangMode,
        val speed: Float,
        val filter: VideoFilter,
        val repetitions: Int,
        val rawId: Long,
        val outputFile: File,
        val returnToGallery: Boolean,
    )
}
