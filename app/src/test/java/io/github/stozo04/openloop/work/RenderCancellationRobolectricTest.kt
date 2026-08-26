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
import kotlin.time.Duration.Companion.seconds
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
 * A [WorkerFactory] substitutes a worker that parks until canceled, so the WorkSpec reaches a real
 * RUNNING state and `cancelUniqueWork` drives the same running → CANCELLED transition a user's
 * "Cancel" tap does. Nothing instantiates the heavy [BoomerangRenderWorker] or touches a codec
 * (encode lives in `BoomerangRenderWorkerTest` on device).
 *
 * Do **not** go back to gating this on a no-op `Configuration.setExecutor`: that left the test's
 * premise resting on a dropped `WorkerWrapper` runnable staying dropped, and it intermittently
 * resolved the WorkSpec to FAILED instead of CANCELLED (~1 in 5 full-suite runs, 2026-08-07).
 *
 * Even with the worker genuinely parked, WorkManager's own stop path still resolves the WorkSpec to
 * FAILED once in a while (the pre-PR sweep of 2026-08-26 caught it again on the same code). That
 * is a race inside WorkManager between the app's cancel and the worker's cancellation, and a user
 * would hit it too — so the contract under test is the **scheduler's** outcome, which maps a
 * FAILED-after-our-own-cancel to [BoomerangRenderWorkResult.Cancelled]
 * ([WorkManagerBoomerangRenderScheduler.cancelRenderWork]). The raw WorkSpec state is asserted
 * only as *finished*; which label WorkManager chose is exactly what the scheduler exists to absorb.
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

        // Through the scheduler's own cancel — the path a user's "Cancel" tap takes — so the
        // scheduler knows this cancel is its own (see the class KDoc).
        scheduler.cancelRenderWork(request.scratch.uuid)

        // observeResult surfaces the terminal outcome; the cancel above must read as Cancelled
        // whether WorkManager filed the WorkSpec as CANCELLED or lost its race and wrote FAILED.
        val result = runBlocking { withTimeout(5.seconds) { scheduler.observeResult(workId).first() } }
        assertEquals(BoomerangRenderWorkResult.Cancelled, result)
        val finalState = workManager.stateOf(workId)
        assertTrue("work must be finished after cancel, was $finalState", finalState.isFinished)
        assertTrue(
            "a cancel must never resolve as success, was $finalState",
            finalState != WorkInfo.State.SUCCEEDED,
        )
    }

    /** Stands in for [BoomerangRenderWorker]: signals it started, then parks until canceled. */
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
