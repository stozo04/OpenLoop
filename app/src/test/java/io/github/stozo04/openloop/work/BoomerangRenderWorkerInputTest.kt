package io.github.stozo04.openloop.work

import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BoomerangRenderWorkerInputTest {

    @Test
    fun `toInputData and from round-trip all fields`() {
        val scratch = ScratchCapture("uuid-abc", File("/cache/scratch/raw_uuid-abc.mp4"))
        val output = File("/files/videos/boom_1_from_2.mp4")
        val request = BoomerangRenderRequest(
            scratch = scratch,
            trimStartMs = 100L,
            trimEndMs = 2_500L,
            mode = BoomerangMode.FORWARD_THEN_REVERSE,
            speed = 1.5f,
            filter = VideoFilter.WARM,
            repetitions = 1,
            rawId = 42L,
            outputFile = output,
            returnToGallery = true,
        )

        val data = BoomerangRenderWorkerInput.toInputData(request)
        val parsed = BoomerangRenderWorkerInput.from(data)

        assertNotNull(parsed)
        assertEquals(scratch.uuid, parsed!!.scratch.uuid)
        assertEquals(scratch.file.absolutePath, parsed.scratch.file.absolutePath)
        assertEquals(100L, parsed.trimStartMs)
        assertEquals(2_500L, parsed.trimEndMs)
        assertEquals(BoomerangMode.FORWARD_THEN_REVERSE, parsed.mode)
        assertEquals(1.5f, parsed.speed, 0f)
        assertEquals(VideoFilter.WARM, parsed.filter)
        assertEquals(1, parsed.repetitions)
        assertEquals(42L, parsed.rawId)
        assertEquals(output.absolutePath, parsed.outputFile.absolutePath)
        assertEquals(true, parsed.returnToGallery)
    }

    @Test
    fun `from returns null when required keys are missing`() {
        assertNull(BoomerangRenderWorkerInput.from(androidx.work.Data.EMPTY))
    }
}
