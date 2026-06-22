package io.github.stozo04.openloop.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric guard tests for [BoomerangRenderWorker] orchestration (Tier 2 #4). Covers input
 * validation, [getForegroundInfo], and the FGS-promotion-denied branch only — never reaches
 * [io.github.stozo04.openloop.media.VideoProcessor.renderBoomerang] (encode stays in
 * [BoomerangRenderWorkerTest] on device).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class BoomerangRenderWorkerRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun doWork_emptyInputData_returnsFailureWithoutEncode() {
        val worker = TestListenableWorkerBuilder<BoomerangRenderWorker>(context)
            .setInputData(Data.EMPTY)
            .build()

        val result = worker.startWork().get(10, TimeUnit.SECONDS)

        assertTrue("expected Result.failure(), was $result", result is ListenableWorker.Result.Failure)
    }

    @Test
    fun doWork_partialInputData_returnsFailureWithoutEncode() {
        val worker = TestListenableWorkerBuilder<BoomerangRenderWorker>(context)
            .setInputData(
                Data.Builder()
                    .putString(BoomerangRenderWorkerKeys.SCRATCH_PATH, "/tmp/scratch.mp4")
                    .build(),
            )
            .build()

        val result = worker.startWork().get(10, TimeUnit.SECONDS)

        assertTrue("expected Result.failure(), was $result", result is ListenableWorker.Result.Failure)
    }

    @Test
    @Config(sdk = [34])
    fun getForegroundInfo_returnsValidForegroundInfo() = runTest {
        val worker = TestListenableWorkerBuilder<BoomerangRenderWorker>(context).build()

        val info = worker.getForegroundInfo()

        assertNotNull(info.notification)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
    }

    @Test
    fun fgsPromotionDenied_failsGracefullyAndDeletesStalePartial() {
        val workDir = File(context.cacheDir, "boomerang_worker_robo_${System.nanoTime()}").apply { mkdirs() }
        try {
            val scratchFile = File(workDir, "raw_fgs_denied.mp4").apply { writeBytes(ByteArray(64)) }
            val rawId = 9_003L
            val outputFile = File(workDir, "boom_stale_partial_from_$rawId.mp4").apply {
                writeBytes(ByteArray(128))
            }

            val renderRequest = BoomerangRenderRequest(
                scratch = ScratchCapture("fgs-denied-${UUID.randomUUID()}", scratchFile),
                trimStartMs = 0L,
                trimEndMs = 500L,
                mode = BoomerangMode.FORWARD,
                speed = 2.0f,
                filter = VideoFilter.ORIGINAL,
                repetitions = 1,
                rawId = rawId,
                outputFile = outputFile,
                returnToGallery = false,
            )

            val worker = TestListenableWorkerBuilder<BoomerangRenderWorker>(context)
                .setInputData(BoomerangRenderWorkerInput.toInputData(renderRequest))
                .setForegroundUpdater { _, _, _ ->
                    immediateFailedFuture(
                        IllegalStateException("foreground service start not allowed (test)"),
                    )
                }
                .build()

            val result = worker.startWork().get(10, TimeUnit.SECONDS)

            assertTrue("expected Result.failure(), was $result", result is ListenableWorker.Result.Failure)
            assertFalse("stale partial output must be deleted", outputFile.exists())
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Dependency-free failed [ListenableFuture] (guava's Futures is not on this classpath). */
    private fun <V> immediateFailedFuture(error: Throwable): ListenableFuture<V> =
        object : ListenableFuture<V> {
            override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun isCancelled(): Boolean = false
            override fun isDone(): Boolean = true
            override fun get(): V = throw ExecutionException(error)
            override fun get(timeout: Long, unit: TimeUnit): V = throw ExecutionException(error)
        }
}
