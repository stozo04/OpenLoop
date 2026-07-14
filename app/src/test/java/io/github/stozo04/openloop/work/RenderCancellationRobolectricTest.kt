package io.github.stozo04.openloop.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The worker executor is a no-op, so the heavy [BoomerangRenderWorker] never instantiates or runs:
 * the enqueued work stays in-flight and `cancelUniqueWork` drives a genuine (not-finished) → CANCELLED
 * transition without any media codecs (encode lives in `BoomerangRenderWorkerTest` on device).
 */
@RunWith(RobolectricTestRunner::class)
class RenderCancellationRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun initWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.ASSERT)
            // Drop the WorkerWrapper runnable so doWork() never runs: the WorkSpec stays in-flight
            // and is cancellable without instantiating BoomerangRenderWorker or touching a codec.
            .setExecutor { /* no-op */ }
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

        // The no-op executor kept the worker from running, so the work is still cancellable — if it
        // had actually run it would be FAILED (no real scratch/codec), i.e. already finished.
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
