package io.github.stozo04.openloop.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric integration test for the render-cancellation path (PR #101 follow-up). Drives a real
 * WorkManager `CANCELLED` transition through [WorkManagerBoomerangRenderScheduler] and asserts the
 * scheduler surfaces [BoomerangRenderWorkResult.Cancelled] end-to-end — the flow plumbing the
 * pure-JVM [RenderWorkResultMappingTest] can only cover for [renderWorkResultOf] in isolation.
 *
 * A [WorkerFactory] substitutes a worker that parks until cancelled, so the WorkSpec reaches a real
 * RUNNING state and `cancelUniqueWork` drives the same running → CANCELLED transition a user's
 * "Cancel" tap does — without instantiating the heavy [BoomerangRenderWorker] or touching a codec
 * (encode lives in `BoomerangRenderWorkerTest` on device).
 *
 * Do **not** go back to gating this on a no-op `Configuration.setExecutor`: that left the test's
 * premise resting on a dropped `WorkerWrapper` runnable staying dropped, and it intermittently
 * resolved the WorkSpec to FAILED instead of CANCELLED (~1 in 5 full-suite runs, 2026-08-07).
 */
@RunWith(RobolectricTestRunner::class)
class RenderCancellationRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val workerStarted = CountDownLatch(1)

    @Before
    fun initWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.ASSERT)
            .setWorkerFactory(ParkedWorkerFactory(workerStarted))
            // Internal WorkManager ops (enqueue/cancel/DB writes, the WorkInfo flow) run inline for
            // deterministic ordering under Robolectric.
            .setTaskExecutor { it.run() }
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun cancelledRenderWork_schedulerEmitsCancelled() {
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerBoomerangRenderScheduler(workManager)
        val request = renderRequest()

        val workId = scheduler.enqueue(request)

        // The substituted worker parks instead of returning, so the work is genuinely running (not
        // merely un-started) when we cancel it — the state a user's "Cancel" tap actually hits.
        assertTrue(
            "substituted worker should have started",
            workerStarted.await(5, TimeUnit.SECONDS),
        )
        assertFalse(
            "work should still be in-flight before cancel",
            workManager.stateOf(workId).isFinished,
        )

        workManager.cancelUniqueWork(request.uniqueWorkName).result.get()
        assertEquals(WorkInfo.State.CANCELLED, workManager.stateOf(workId))

        // observeResult maps the CANCELLED WorkInfo through renderWorkResultOf and surfaces it.
        val result = runBlocking { withTimeout(5_000L) { scheduler.observeResult(workId).first() } }
        assertEquals(BoomerangRenderWorkResult.Cancelled, result)
    }

    /** Stands in for [BoomerangRenderWorker]: signals it started, then parks until cancelled. */
    private class ParkedWorkerFactory(private val started: CountDownLatch) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = object : CoroutineWorker(appContext, workerParameters) {
            override suspend fun doWork(): Result {
                started.countDown()
                awaitCancellation()
            }
        }
    }

    /** [WorkManager.getWorkInfoById] is nullable (id may be unknown); the work always exists here. */
    private fun WorkManager.stateOf(workId: UUID): WorkInfo.State =
        requireNotNull(getWorkInfoById(workId).get()) { "no WorkInfo for $workId" }.state

    private fun renderRequest(): BoomerangRenderRequest {
        // Paths only — the worker never runs, so no files are read or written.
        val scratchUuid = "cancel-${UUID.randomUUID()}"
        return BoomerangRenderRequest(
            scratch = ScratchCapture(scratchUuid, File(context.cacheDir, "$scratchUuid.mp4")),
            trimStartMs = 0L,
            trimEndMs = 500L,
            mode = BoomerangMode.FORWARD,
            speed = 2.0f,
            filter = VideoFilter.ORIGINAL,
            repetitions = 1,
            rawId = 7_001L,
            outputFile = File(context.cacheDir, "boom_1_from_7001_$scratchUuid.mp4"),
            returnToGallery = false,
        )
    }
}
