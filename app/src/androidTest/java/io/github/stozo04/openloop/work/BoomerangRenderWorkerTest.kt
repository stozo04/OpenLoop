package io.github.stozo04.openloop.work

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.SyntheticVideoFixtures
import io.github.stozo04.openloop.media.VideoFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Instrumented integration test for [BoomerangRenderWorker] (Issue #40).
 *
 * **Run from Android Studio:** connect a physical device (recommended — real MediaCodec path),
 * open this class, click the green run icon on [enqueueTinyForwardClip_succeedsAndDiscardsScratch],
 * pick your device. Expect **30–120 s** on a phone (encode + thumbnail registration).
 *
 * **What green means:** WorkManager reports [WorkInfo.State.SUCCEEDED], the boomerang MP4 exists
 * and is non-empty, and the scratch source was deleted after success.
 */
@RunWith(AndroidJUnit4::class)
class BoomerangRenderWorkerTest {

    @get:Rule
    val grantNotificationsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.cacheDir, "boomerang_worker_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun enqueueTinyForwardClip_succeedsAndDiscardsScratch() {
        val scratchFile = File(workDir, "raw_worker_fixture.mp4")
        val fixtureOk = SyntheticVideoFixtures.generateLumaRampClip(
            dest = scratchFile,
            width = 320,
            height = 240,
            frameCount = 8,
            fps = 12,
        )
        assumeTrue("synthetic H.264 fixture unavailable on this device/codec", fixtureOk)

        val durationMs = SyntheticVideoFixtures.durationMs(scratchFile)
        assumeTrue("fixture duration unreadable", durationMs > 0L)

        val scratchUuid = "worker-test-${UUID.randomUUID()}"
        val scratch = ScratchCapture(scratchUuid, scratchFile)
        val rawId = 9_001L
        val outputFile = File(workDir, "boom_${System.currentTimeMillis()}_from_$rawId.mp4")

        val renderRequest = BoomerangRenderRequest(
            scratch = scratch,
            trimStartMs = 0L,
            trimEndMs = durationMs,
            mode = BoomerangMode.FORWARD,
            speed = 2.0f,
            filter = VideoFilter.ORIGINAL,
            repetitions = 1,
            rawId = rawId,
            outputFile = outputFile,
            returnToGallery = false,
        )

        val workRequest = OneTimeWorkRequestBuilder<BoomerangRenderWorker>()
            .setInputData(BoomerangRenderWorkerInput.toInputData(renderRequest))
            .addTag(WORKER_TEST_TAG)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(workRequest).result.get()

        val finished = awaitTerminalWorkInfo(workManager, workRequest.id, TIMEOUT_MS)
        assertNotNull("worker did not finish within ${TIMEOUT_MS / 1000}s", finished)
        assertEquals(
            "expected SUCCEEDED, was ${finished!!.state} (runAttempt=${finished.runAttemptCount})",
            WorkInfo.State.SUCCEEDED,
            finished.state,
        )

        val outputPath = finished.outputData.getString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH)
        assertNotNull(outputPath)
        val output = File(outputPath!!)
        assertTrue("boomerang output missing: ${output.absolutePath}", output.exists())
        assertTrue("boomerang output empty", output.length() > 0L)
        assertTrue(
            "output should be readable video",
            SyntheticVideoFixtures.durationMs(output) > 0L,
        )

        assertFalse(
            "scratch should be discarded after successful render",
            scratchFile.exists(),
        )

        val progressPercent = finished.progress.getInt(BoomerangRenderWorkerKeys.PROGRESS_PERCENT, -1)
        assertTrue(
            "expected progress published during run, got $progressPercent",
            progressPercent in 0..100,
        )
    }

    @Test
    fun invalidWorkerInput_failsImmediately() {
        val workRequest = OneTimeWorkRequestBuilder<BoomerangRenderWorker>()
            .setInputData(androidx.work.Data.EMPTY)
            .addTag(WORKER_TEST_TAG)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(workRequest).result.get()

        val finished = awaitTerminalWorkInfo(workManager, workRequest.id, 30_000L)
        assertNotNull(finished)
        assertEquals(WorkInfo.State.FAILED, finished!!.state)
    }

    @Test
    fun missingScratchFile_failsWithoutPlayableOutput() {
        val rawId = 9_002L
        val outputFile = File(workDir, "boom_${System.currentTimeMillis()}_from_$rawId.mp4")
        val missingScratch = File(workDir, "definitely_missing_scratch.mp4")
        assumeTrue("stale output should not exist", !outputFile.exists())

        val renderRequest = BoomerangRenderRequest(
            scratch = ScratchCapture("missing-scratch", missingScratch),
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

        val workRequest = OneTimeWorkRequestBuilder<BoomerangRenderWorker>()
            .setInputData(BoomerangRenderWorkerInput.toInputData(renderRequest))
            .addTag(WORKER_TEST_TAG)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(workRequest).result.get()

        val finished = awaitTerminalWorkInfo(workManager, workRequest.id, TIMEOUT_MS)
        assertNotNull(finished)
        assertEquals(WorkInfo.State.FAILED, finished!!.state)
        assertTrue(
            "partial output must not be a playable boomerang",
            !outputFile.exists() || SyntheticVideoFixtures.durationMs(outputFile) <= 0L,
        )
    }

    private fun awaitTerminalWorkInfo(
        workManager: WorkManager,
        workId: UUID,
        timeoutMs: Long,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            latest = workManager.getWorkInfoById(workId).get()
            if (latest?.state?.isFinished == true) return latest
            Thread.sleep(POLL_MS)
        }
        return latest?.takeIf { it.state.isFinished }
    }

    private companion object {
        const val WORKER_TEST_TAG = "boomerang_render_worker_test"
        const val TIMEOUT_MS = 180_000L
        const val POLL_MS = 500L
    }
}
