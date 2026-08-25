package io.github.stozo04.openloop.work

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.stozo04.openloop.work.BoomerangRenderWorkerKeys.PROGRESS_PERCENT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.UUID

/** WorkManager-backed [BoomerangRenderScheduler] (Issue #40). */
class WorkManagerBoomerangRenderScheduler(
    private val workManager: WorkManager,
) : BoomerangRenderScheduler {

    override fun enqueue(request: BoomerangRenderRequest): UUID {
        val workRequest = OneTimeWorkRequestBuilder<BoomerangRenderWorker>()
            .setInputData(BoomerangRenderWorkerInput.toInputData(request))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            request.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
        return workRequest.id
    }

    override fun observeProgress(workId: UUID): Flow<Float> =
        workManager.getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .map { info ->
                val percent = info.progress.getInt(PROGRESS_PERCENT, 0).coerceIn(0, 100)
                percent / 100f
            }

    override fun observeResult(workId: UUID): Flow<BoomerangRenderWorkResult> =
        workManager.getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .mapNotNull { info -> renderWorkResultOf(info.state, info.outputData) }
            .distinctUntilChanged()

    override fun cancelRenderWork(scratchUuid: String) {
        workManager.cancelUniqueWork("render_$scratchUuid")
    }

    companion object {
        const val WORK_TAG = "boomerang_render"
    }
}

/**
 * Terminal [WorkInfo] state + output [Data] → [BoomerangRenderWorkResult], or null while the work
 * is still in flight. FAILED reads the reason the worker attached via `Result.failure(Data)`
 * (null when the work died without one — process death, or work persisted by an older version);
 * CANCELLED is a distinct [BoomerangRenderWorkResult.Cancelled] (user intent, not a failure — no
 * Crashlytics beacon). Top-level for JVM unit testing.
 */
internal fun renderWorkResultOf(state: WorkInfo.State, outputData: Data): BoomerangRenderWorkResult? =
    when (state) {
        WorkInfo.State.SUCCEEDED -> {
            outputData.getString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH)?.let { path ->
                BoomerangRenderWorkResult.Success(
                    outputFile = File(path),
                    returnToGallery = outputData.getBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, false),
                )
            }
        }
        WorkInfo.State.FAILED -> BoomerangRenderWorkResult.Failure(
            reason = outputData.getString(BoomerangRenderWorkerKeys.FAILURE_REASON),
            workerReportedCause = outputData.getBoolean(BoomerangRenderWorkerKeys.FAILURE_REPORTED_CAUSE, false),
        )
        WorkInfo.State.CANCELLED -> BoomerangRenderWorkResult.Cancelled
        else -> null
    }
