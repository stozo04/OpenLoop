package io.github.stozo04.openloop.work

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.stozo04.openloop.BuildConfig
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
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
            ?: return failureResult(REASON_INPUT_PARSE_FAILED, reportedCause = false)

        try {
            setForeground(BoomerangRenderNotifications.createForegroundInfo(applicationContext, 0))
        } catch (e: IllegalStateException) {
            // setForegroundAsync's documented failure: "will fail with an IllegalStateException when
            // the process is subject to foreground service restrictions" (ListenableWorker docs) —
            // concretely ForegroundServiceStartNotAllowedException (its API 31+ subclass) when
            // WorkManager re-runs persisted work after process death / reboot while the app is
            // backgrounded. The render needs the FGS to survive, so fail gracefully instead of
            // letting the exception escape doWork with no cleanup or telemetry (Issue #67 F1).
            // The ViewModel's failBackToEditor reporter is gone in that scenario (process died),
            // so this is the only Crashlytics signal for the lost render.
            Log.e(TAG, "FGS promotion denied; failing render without retry", e)
            deletePartialOutput(parsed.outputFile) // a pre-death attempt may have left a partial
            val outcome = "fgs_promotion_denied: ${e.javaClass.simpleName}"
            ReverseCrashlytics.reportSaveFailure(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                source = parsed.scratch.file,
                trimStartMs = parsed.trimStartMs,
                trimEndMs = parsed.trimEndMs,
                outcome = outcome,
                cause = e,
            )
            return failureResult(outcome, reportedCause = true)
        }

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
                failureResult(reportRenderFailure(parsed, "io", e), reportedCause = true)
            } catch (e: ExportException) {
                // Media3's documented async failure type (Transformer.Listener.onError, rethrown by
                // runTransformer). It extends Exception directly — neither IOException nor
                // RuntimeException — so without this branch it escaped doWork entirely, skipping
                // deletePartialOutput and surfacing via WorkManager's generic thrown-exception path
                // (observed on the S23, RESEARCH.md §6.2).
                progressPublisher.cancel()
                Log.e(TAG, "Boomerang render failed (export)", e)
                deletePartialOutput(parsed.outputFile)
                failureResult(reportRenderFailure(parsed, "export", e), reportedCause = true)
            } catch (e: RuntimeException) {
                progressPublisher.cancel()
                Log.e(TAG, "Boomerang render failed", e)
                deletePartialOutput(parsed.outputFile)
                failureResult(reportRenderFailure(parsed, "runtime", e), reportedCause = true)
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
        try {
            setForeground(BoomerangRenderNotifications.createForegroundInfo(applicationContext, clamped))
        } catch (e: IllegalStateException) {
            // The FGS is already promoted by doWork's guarded call; a refresh can still race a
            // service stop (cancellation, Android 15+ mediaProcessing timeout). Losing one
            // notification update must not kill an otherwise-healthy render — without this catch
            // the exception escapes the progressPublisher coroutine and cancels the whole
            // coroutineScope mid-encode (Issue #67 F1).
            Log.w(TAG, "FGS notification refresh skipped at $clamped%", e)
        }
    }

    /**
     * Record the worker's real render failure to Crashlytics as a non-fatal, returning the outcome
     * string so [failureResult] can carry it across WorkManager. Without this the only signal was
     * the ViewModel's bare `BoomerangRenderWorkResult.Failure` (WorkManager does not carry the
     * worker's *exception* across), reported with a synthetic stand-in cause — so the actual
     * codec/Transformer exception (e.g. LG LM-X540 `IllegalArgumentException: start failed`,
     * Crashlytics 47233ad7) lived only in this process's logcat ("details in BoomerangRenderWorker
     * log") and never reached Firebase. This carries the genuine stack trace.
     */
    private fun reportRenderFailure(parsed: BoomerangRenderWorkerInput.Parsed, kind: String, cause: Throwable): String {
        val outcome = "render_failed_$kind: ${cause.javaClass.simpleName}: ${cause.message}"
        ReverseCrashlytics.reportSaveFailure(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = parsed.scratch.file,
            trimStartMs = parsed.trimStartMs,
            trimEndMs = parsed.trimEndMs,
            outcome = outcome,
            cause = cause,
        )
        return outcome
    }

    /**
     * [Result.failure] with the failure [reason] attached as output [Data], so the observing
     * ViewModel can report *why* instead of a catch-all beacon (Crashlytics 47233ad7 triage).
     * [reportedCause] marks whether this worker already recorded the genuine exception to
     * Crashlytics — when true the observer suppresses its duplicate synthetic-cause non-fatal.
     */
    private fun failureResult(reason: String, reportedCause: Boolean): Result =
        Result.failure(
            Data.Builder()
                .putString(BoomerangRenderWorkerKeys.FAILURE_REASON, reason.take(FAILURE_REASON_MAX_CHARS))
                .putBoolean(BoomerangRenderWorkerKeys.FAILURE_REPORTED_CAUSE, reportedCause)
                .build(),
        )

    private fun deletePartialOutput(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete partial boomerang output: ${file.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "BoomerangRenderWorker"
        val PROGRESS_EMIT_INTERVAL = 1000.milliseconds

        /** [BoomerangRenderWorkerInput.from] rejected the input Data — nothing was rendered. */
        const val REASON_INPUT_PARSE_FAILED = "input_parse_failed"

        /** WorkManager output Data is capped at ~10 KB total; exception messages can be long. */
        const val FAILURE_REASON_MAX_CHARS = 512
    }
}
