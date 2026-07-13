package io.github.stozo04.openloop.work

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guard tests for [renderWorkResultOf] — the WorkInfo → [BoomerangRenderWorkResult] mapping
 * that carries the worker's failure reason across WorkManager (Crashlytics 47233ad7 triage).
 */
class RenderWorkResultMappingTest {

    @Test
    fun `FAILED with attached reason maps to Failure carrying reason and reported flag`() {
        val data = Data.Builder()
            .putString(BoomerangRenderWorkerKeys.FAILURE_REASON, "render_failed_export: ExportException: boom")
            .putBoolean(BoomerangRenderWorkerKeys.FAILURE_REPORTED_CAUSE, true)
            .build()

        val result = renderWorkResultOf(WorkInfo.State.FAILED, data)

        val failure = result as BoomerangRenderWorkResult.Failure
        assertEquals("render_failed_export: ExportException: boom", failure.reason)
        assertTrue(failure.workerReportedCause)
    }

    @Test
    fun `FAILED without data maps to Failure with null reason, not worker-reported`() {
        val result = renderWorkResultOf(WorkInfo.State.FAILED, Data.EMPTY)

        val failure = result as BoomerangRenderWorkResult.Failure
        assertNull(failure.reason)
        assertFalse(failure.workerReportedCause)
    }

    @Test
    fun `CANCELLED maps to Failure with the cancelled reason, not worker-reported`() {
        val result = renderWorkResultOf(WorkInfo.State.CANCELLED, Data.EMPTY)

        val failure = result as BoomerangRenderWorkResult.Failure
        assertEquals(WorkManagerBoomerangRenderScheduler.REASON_CANCELLED, failure.reason)
        assertFalse(failure.workerReportedCause)
    }

    @Test
    fun `SUCCEEDED with output path maps to Success`() {
        val data = Data.Builder()
            .putString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH, "/files/videos/boom_1_from_2.mp4")
            .putBoolean(BoomerangRenderWorkerKeys.RETURN_TO_GALLERY, true)
            .build()

        val result = renderWorkResultOf(WorkInfo.State.SUCCEEDED, data)

        val success = result as BoomerangRenderWorkResult.Success
        assertEquals("/files/videos/boom_1_from_2.mp4", success.outputFile.path.replace('\\', '/'))
        assertTrue(success.returnToGallery)
    }

    @Test
    fun `SUCCEEDED without output path maps to null`() {
        assertNull(renderWorkResultOf(WorkInfo.State.SUCCEEDED, Data.EMPTY))
    }

    @Test
    fun `non-terminal states map to null`() {
        assertNull(renderWorkResultOf(WorkInfo.State.ENQUEUED, Data.EMPTY))
        assertNull(renderWorkResultOf(WorkInfo.State.RUNNING, Data.EMPTY))
        assertNull(renderWorkResultOf(WorkInfo.State.BLOCKED, Data.EMPTY))
    }
}
