package io.github.stozo04.openloop.work

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.stozo04.openloop.media.MediaComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import androidx.work.ListenableWorker.Result
import kotlin.time.Duration.Companion.milliseconds

/**
 * Long-running WorkManager worker that Loopifies a scratch clip under a `mediaProcessing` FGS
 * (Issue #40). Orchestration only — delegates encode to [io.github.stozo04.openloop.media.VideoProcessor].
 *
 * Retry policy: always [Result.failure] on user-visible render errors — never [Result.retry]
 * (Google: retry is for transient failures only).
 */
@UnstableApi
class BoomerangRenderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val videoStorage = MediaComponents.buildVideoStorageRepository(context)
    private val videoProcessor = MediaComponents.buildVideoProcessor(context)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        BoomerangRenderNotifications.createForegroundInfo(applicationContext, progressPercent = 0)

    override suspend fun doWork(): Result {
        val parsed = BoomerangRenderWorkerInput.from(inputData)
            ?: return Result.failure()

        setForeground(BoomerangRenderNotifications.createForegroundInfo(applicationContext, 0))

        return coroutineScope {
            val latestProgress = AtomicReference(0f)
            val progressPublisher = launch {
                var lastPublishedPercent = -1
                while (isActive) {
                    val fraction = latestProgress.get().coerceIn(0f, 1f)
                    val percent = (fraction * 100f).toInt()
                    if (percent != lastPublishedPercent) {
                        publishProgress(percent)
                        lastPublishedPercent = percent
                    }
                    if (fraction >= 1f) break
                    delay(PROGRESS_EMIT_INTERVAL)
                }
            }

            try {
                videoProcessor.renderBoomerang(
                    source = parsed.scratch.file,
                    trimStartMs = parsed.trimStartMs,
                    trimEndMs = parsed.trimEndMs,
                    mode = parsed.mode,
                    speed = parsed.speed,
                    filter = parsed.filter,
                    repetitions = parsed.repetitions,
                    outputFile = parsed.outputFile,
                    onProgress = { fraction -> latestProgress.set(fraction) },
                )

                latestProgress.set(1f)
                progressPublisher.join()

                videoStorage.registerBoomerang(parsed.outputFile, parsed.rawId)
                    ?: throw IOException("Failed to register boomerang ${parsed.outputFile.name}")

                videoStorage.discardScratch(parsed.scratch)

                // The original raw video is no longer needed after a successful boomerang render.
                videoStorage.deleteRawVideo(parsed.rawId)

                Result.success(
                    Data.Builder()
                        .putString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH, parsed.outputFile.absolutePath)
                        .putBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, parsed.returnToGallery)
                        .build(),
                )
            } catch (e: CancellationException) {
                progressPublisher.cancel()
                deletePartialOutput(parsed.outputFile)
                throw e // never swallow cancellation (Lesson 013)
            } catch (e: IOException) {
                progressPublisher.cancel()
                Log.e(TAG, "Boomerang render failed (IO)", e)
                deletePartialOutput(parsed.outputFile)
                Result.failure()
            } catch (e: ExportException) {
                // Media3's documented async failure type (Transformer.Listener.onError, rethrown by
                // runTransformer). It extends Exception directly — neither IOException nor
                // RuntimeException — so without this branch it escaped doWork entirely, skipping
                // deletePartialOutput and surfacing via WorkManager's generic thrown-exception path
                // (observed on the S23, RESEARCH.md §6.2).
                progressPublisher.cancel()
                Log.e(TAG, "Boomerang render failed (export)", e)
                deletePartialOutput(parsed.outputFile)
                Result.failure()
            } catch (e: RuntimeException) {
                progressPublisher.cancel()
                Log.e(TAG, "Boomerang render failed", e)
                deletePartialOutput(parsed.outputFile)
                Result.failure()
            }
        }
    }

    private suspend fun publishProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        setProgressAsync(
            Data.Builder()
                .putInt(BoomerangRenderWorkerKeys.PROGRESS_PERCENT, clamped)
                .build(),
        )
        setForeground(BoomerangRenderNotifications.createForegroundInfo(applicationContext, clamped))
    }

    private fun deletePartialOutput(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete partial boomerang output: ${file.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "BoomerangRenderWorker"
        val PROGRESS_EMIT_INTERVAL = 1000.milliseconds
    }
}
