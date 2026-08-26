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
import java.util.concurrent.ConcurrentHashMap

/** WorkManager-backed [BoomerangRenderScheduler] (Issue #40). */
class WorkManagerBoomerangRenderScheduler(
    private val workManager: WorkManager,
) : BoomerangRenderScheduler {

    /** Work id → unique name, for every render this process enqueued. */
    private val workNames = ConcurrentHashMap<UUID, String>()

    /**
     * Unique names this process asked WorkManager to cancel. A running worker that the app cancels
     * is usually resolved `CANCELLED`, but WorkManager's stop path races the worker's own
     * cancellation and sometimes lands on `FAILED` instead (seen ~1 in 5 full-suite runs of
     * `RenderCancellationRobolectricTest`, 2026-08-07 and 2026-08-26). The app knows it cancelled,
     * so [observeResult] reads a `FAILED` that follows its own cancel as the cancel — user intent
     * beats the label WorkManager put on the corpse. Lesson 029.
     */
    private val cancelledWorkNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun enqueue(request: BoomerangRenderRequest): UUID {
        val workRequest = OneTimeWorkRequestBuilder<BoomerangRenderWorker>()
            .setInputData(BoomerangRenderWorkerInput.toInputData(request))
            .addTag(WORK_TAG)
            .build()
        workNames[workRequest.id] = request.uniqueWorkName
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
            .mapNotNull { info ->
                renderWorkResultOf(
                    state = info.state,
                    outputData = info.outputData,
                    cancelledByApp = workNames[workId]?.let(cancelledWorkNames::contains) == true,
                )
            }
            .distinctUntilChanged()

    override fun cancelRenderWork(scratchUuid: String) {
        val name = "render_$scratchUuid"
        cancelledWorkNames += name
        workManager.cancelUniqueWork(name)
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
 * Crashlytics beacon). A FAILED that follows the app's own cancel ([cancelledByApp]) is that
 * cancel too — see [WorkManagerBoomerangRenderScheduler.cancelledWorkNames] for the WorkManager
 * race that makes this necessary. Top-level for JVM unit testing.
 */
internal fun renderWorkResultOf(
    state: WorkInfo.State,
    outputData: Data,
    cancelledByApp: Boolean = false,
): BoomerangRenderWorkResult? =
    when (state) {
        WorkInfo.State.SUCCEEDED -> {
            outputData.getString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH)?.let { path ->
                BoomerangRenderWorkResult.Success(
                    outputFile = File(path),
                    returnToGallery = outputData.getBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, false),
                )
            }
        }
        WorkInfo.State.FAILED -> if (cancelledByApp) {
            BoomerangRenderWorkResult.Cancelled
        } else {
            BoomerangRenderWorkResult.Failure(
                reason = outputData.getString(BoomerangRenderWorkerKeys.FAILURE_REASON),
                workerReportedCause = outputData.getBoolean(BoomerangRenderWorkerKeys.FAILURE_REPORTED_CAUSE, false),
            )
        }
        WorkInfo.State.CANCELLED -> BoomerangRenderWorkResult.Cancelled
        else -> null
    }
