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
    fun `CANCELLED maps to the distinct Cancelled outcome, not a Failure`() {
        val result = renderWorkResultOf(WorkInfo.State.CANCELLED, Data.EMPTY)

        // A cancel is user intent, not an error — the observer must not treat it as a failure
        // (no Crashlytics beacon, no SaveFailed).
        assertEquals(BoomerangRenderWorkResult.Cancelled, result)
    }

    @Test
    fun `FAILED after the app's own cancel is the cancel, whatever WorkManager labelled it`() {
        // WorkManager's stop path can resolve an app-cancelled running worker as FAILED (the
        // Lesson 029 race). The user tapped Cancel; they must land back in the editor, not on
        // SaveFailed with a Crashlytics beacon.
        val data = Data.Builder()
            .putString(BoomerangRenderWorkerKeys.FAILURE_REASON, "whatever the corpse says")
            .build()

        assertEquals(
            BoomerangRenderWorkResult.Cancelled,
            renderWorkResultOf(WorkInfo.State.FAILED, data, cancelledByApp = true),
        )
        // The flag only reinterprets FAILED — a success is still a success.
        val success = Data.Builder().putString(BoomerangRenderWorkerKeys.OUTPUT_FILE_PATH, "/tmp/x.mp4").build()
        assertTrue(
            renderWorkResultOf(WorkInfo.State.SUCCEEDED, success, cancelledByApp = true)
                is BoomerangRenderWorkResult.Success,
        )
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
