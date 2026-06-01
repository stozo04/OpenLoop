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
            .map { info -> info.toRenderResult() }
            .filterNotNull()
            .distinctUntilChanged()

    override fun cancelRenderWork(scratchUuid: String) {
        workManager.cancelUniqueWork("render_$scratchUuid")
    }

    private fun WorkInfo.toRenderResult(): BoomerangRenderWorkResult? =
        when (state) {
            WorkInfo.State.SUCCEEDED -> {
                val path = outputData.getString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH) ?: return null
                BoomerangRenderWorkResult.Success(
                    outputFile = File(path),
                    returnToGallery = outputData.getBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, false),
                )
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> BoomerangRenderWorkResult.Failure
            else -> null
        }

    companion object {
        const val WORK_TAG = "boomerang_render"
    }
}
