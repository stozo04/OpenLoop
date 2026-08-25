package io.github.stozo04.openloop.work

import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.SpeedKey
import io.github.stozo04.openloop.media.VideoFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The curve has to survive the trip through WorkManager [androidx.work.Data] intact. It is the one
 * hop in the feature where the object is destroyed and rebuilt from primitives, so a mistake here
 * silently renders the wrong speed instead of failing loudly.
 */
class BoomerangRenderWorkerInputCurveTest {

    private fun request(curve: SpeedCurve?) = BoomerangRenderRequest(
        scratch = ScratchCapture("uuid", File("/tmp/scratch.mp4")),
        trimStartMs = 0L,
        trimEndMs = 3_800L,
        mode = BoomerangMode.FORWARD_THEN_REVERSE,
        speed = 2f,
        curve = curve,
        filter = VideoFilter.ORIGINAL,
        repetitions = 1,
        rawId = 7L,
        outputFile = File("/tmp/out.mp4"),
        returnToGallery = false,
    )

    @Test
    fun `a curve round-trips through Data with every key intact`() {
        val curve = SpeedCurve(
            listOf(
                SpeedKey(0f, 0.6f),
                SpeedKey(0.3f, 1.0f),
                SpeedKey(0.5f, 0.5f),
                SpeedKey(0.7f, 1.4f),
                SpeedKey(1f, 0.6f),
            ),
        )
        val parsed = BoomerangRenderWorkerInput.from(BoomerangRenderWorkerInput.toInputData(request(curve)))
        assertNotNull(parsed)
        assertEquals(curve.keys, parsed!!.curve?.keys)
        assertEquals(curve.flatten(), parsed.effectiveCurve.flatten(), 1e-4f)
    }

    @Test
    fun `no curve means Constant mode and the flat speed survives`() {
        val parsed = BoomerangRenderWorkerInput.from(BoomerangRenderWorkerInput.toInputData(request(null)))
        assertNotNull(parsed)
        assertNull(parsed!!.curve)
        assertEquals(2f, parsed.effectiveCurve.flatten(), 1e-4f)
    }

    @Test
    fun `the effective curve the worker renders matches the one the editor held`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 0.25f), SpeedKey(1f, 3f)))
        val parsed = BoomerangRenderWorkerInput.from(BoomerangRenderWorkerInput.toInputData(request(curve)))
        assertEquals(curve.speedAt(0.5f), parsed!!.effectiveCurve.speedAt(0.5f), 1e-4f)
        assertEquals(curve.maxSpeed(), parsed.effectiveCurve.maxSpeed(), 1e-4f)
    }
}
