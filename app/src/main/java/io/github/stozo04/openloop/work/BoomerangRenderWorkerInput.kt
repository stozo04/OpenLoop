package io.github.stozo04.openloop.work

import androidx.work.Data
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.SpeedKey
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

    /**
     * The speed curve, flattened to an **interleaved** `[t0, speed0, t1, speed1, …]` float array —
     * `Data` has no object support and this avoids two parallel arrays that could arrive at different
     * lengths. Absent (or odd-length, which can only mean corruption) means Constant mode, and [SPEED]
     * is authoritative.
     */
    const val CURVE_POINTS = "curve_points"
    const val FILTER = "filter"
    const val REPETITIONS = "repetitions"
    const val RAW_ID = "raw_id"
    const val OUTPUT_PATH = "output_path"
    const val RETURN_TO_GALLERY = "return_to_gallery"

    /** Worker → UI progress (0..100). */
    const val PROGRESS_PERCENT = "progress_percent"

    /** Success output — absolute path to the registered boomerang MP4. */
    const val OUTPUT_FILE_PATH = "output_file_path"

    /**
     * Failure output — why the render failed (the worker's failure `outcome` string, the same value
     * it also sends to Crashlytics under the `reverse_outcome` custom key).
     */
    const val FAILURE_REASON = "failure_reason"

    /** Failure output — true when the worker already recorded the genuine cause to Crashlytics. */
    const val FAILURE_REPORTED_CAUSE = "failure_reported_cause"
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
            .apply {
                request.curve?.let { putFloatArray(BoomerangRenderWorkerKeys.CURVE_POINTS, it.toFloatArray()) }
            }
            .putString(BoomerangRenderWorkerKeys.FILTER, request.filter.name)
            .putInt(BoomerangRenderWorkerKeys.REPETITIONS, request.repetitions)
            .putLong(BoomerangRenderWorkerKeys.RAW_ID, request.rawId)
            .putString(BoomerangRenderWorkerKeys.OUTPUT_PATH, request.outputFile.absolutePath)
            .putBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, request.returnToGallery)
            .build()

    /** Interleave a curve's keys into `[t0, speed0, t1, speed1, …]`. */
    private fun SpeedCurve.toFloatArray(): FloatArray {
        val flat = FloatArray(keys.size * 2)
        keys.forEachIndexed { index, key ->
            flat[index * 2] = key.t
            flat[index * 2 + 1] = key.speed
        }
        return flat
    }

    /**
     * Rebuild a curve from the interleaved array. Returns null for absent, odd-length (corrupt), or
     * under-two-point data — every one of which the caller treats as Constant mode rather than as a
     * failure, because a malformed extra must never cost the user their save.
     */
    private fun curveFrom(data: Data): SpeedCurve? {
        val flat = data.getFloatArray(BoomerangRenderWorkerKeys.CURVE_POINTS) ?: return null
        if (flat.size < 4 || flat.size % 2 != 0) return null
        val keys = (flat.indices step 2).map { SpeedKey(t = flat[it], speed = flat[it + 1]) }
        return SpeedCurve(keys)
    }

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
            curve = curveFrom(data),
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
        val curve: SpeedCurve?,
        val filter: VideoFilter,
        val repetitions: Int,
        val rawId: Long,
        val outputFile: File,
        val returnToGallery: Boolean,
    ) {
        /** The speed the renderer applies — curve, or [speed] as a flat one (PRD-speed-curves.md D-2). */
        val effectiveCurve: SpeedCurve get() = curve ?: SpeedCurve.flat(speed)
    }
}
