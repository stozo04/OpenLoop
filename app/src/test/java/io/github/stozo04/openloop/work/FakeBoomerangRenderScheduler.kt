package io.github.stozo04.openloop.work

import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.media.VideoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [BoomerangRenderScheduler] for JVM unit tests. Runs the same steps as
 * [BoomerangRenderWorker] inline on the provided [scope] (typically the test dispatcher).
 */
class FakeBoomerangRenderScheduler(
    private val processor: VideoProcessor,
    private val storage: VideoStorageRepository,
    private val scope: CoroutineScope,
) : BoomerangRenderScheduler {

    var enqueueCount: Int = 0
        private set

    private val progressFlows = ConcurrentHashMap<UUID, MutableStateFlow<Float>>()
    private val resultFlows = ConcurrentHashMap<UUID, MutableSharedFlow<BoomerangRenderWorkResult>>()

    override fun enqueue(request: BoomerangRenderRequest): UUID {
        enqueueCount++
        val workId = UUID.randomUUID()
        val progress = MutableStateFlow(0f)
        val results = MutableSharedFlow<BoomerangRenderWorkResult>(replay = 1)
        progressFlows[workId] = progress
        resultFlows[workId] = results

        scope.launch {
            try {
                processor.renderBoomerang(
                    source = request.scratch.file,
                    trimStartMs = request.trimStartMs,
                    trimEndMs = request.trimEndMs,
                    mode = request.mode,
                    speed = request.speed,
                    filter = request.filter,
                    repetitions = request.repetitions,
                    outputFile = request.outputFile,
                    onProgress = { fraction -> progress.value = fraction },
                )
                storage.registerBoomerang(request.outputFile, request.rawId)
                    ?: throw IOException("Failed to register boomerang")
                storage.discardScratch(request.scratch)
                results.emit(
                    BoomerangRenderWorkResult.Success(
                        outputFile = request.outputFile,
                        returnToGallery = request.returnToGallery,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request.outputFile.exists()) {
                    request.outputFile.delete()
                }
                results.emit(BoomerangRenderWorkResult.Failure)
            }
        }
        return workId
    }

    override fun observeProgress(workId: UUID): Flow<Float> =
        progressFlows[workId]?.asStateFlow() ?: emptyFlow()

    override fun observeResult(workId: UUID): Flow<BoomerangRenderWorkResult> =
        resultFlows[workId]?.asSharedFlow() ?: emptyFlow()

    override fun cancelRenderWork(scratchUuid: String) = Unit
}
