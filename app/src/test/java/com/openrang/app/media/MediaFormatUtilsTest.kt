package com.openrang.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the defensive [resolveFrameRate] / [resolveRotationDegrees] logic. The real
 * `MediaFormat` is a JVM stub in unit tests, so the branching is extracted behind lambdas that
 * simulate a track that stores `KEY_FRAME_RATE` as an Int, as a Float, or not at all — and a
 * `getInteger` that throws [ClassCastException] when the stored type doesn't match (the exact
 * device/codec behavior this guards against).
 */
class MediaFormatUtilsTest {

    // ── resolveFrameRate ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `frame rate reads the integer value when present as an int`() {
        val fps = resolveFrameRate(
            containsFrameRate = true,
            readInt = { 24 },
            readFloat = { throw ClassCastException("stored as int") },
            default = DEFAULT_FRAME_RATE,
        )
        assertEquals(24, fps)
    }

    @Test
    fun `frame rate falls back to the float value when the int read throws`() {
        // The bug being fixed: KEY_FRAME_RATE stored as a Float → getInteger throws ClassCastException.
        val fps = resolveFrameRate(
            containsFrameRate = true,
            readInt = { throw ClassCastException("stored as float") },
            readFloat = { 29.97f },
            default = DEFAULT_FRAME_RATE,
        )
        assertEquals(29, fps) // 29.97 → 29 via toInt()
    }

    @Test
    fun `frame rate uses the default when both reads throw`() {
        val fps = resolveFrameRate(
            containsFrameRate = true,
            readInt = { throw ClassCastException() },
            readFloat = { throw ClassCastException() },
            default = 30,
        )
        assertEquals(30, fps)
    }

    @Test
    fun `frame rate uses the default when the key is absent`() {
        val fps = resolveFrameRate(
            containsFrameRate = false,
            readInt = { throw AssertionError("must not read an absent key") },
            readFloat = { throw AssertionError("must not read an absent key") },
            default = 30,
        )
        assertEquals(30, fps)
    }

    @Test
    fun `frame rate uses the default for a non-positive value`() {
        val fps = resolveFrameRate(
            containsFrameRate = true,
            readInt = { 0 },
            readFloat = { 0f },
            default = 30,
        )
        assertEquals(30, fps)
    }

    // ── resolveRotationDegrees ───────────────────────────────────────────────────────────────────

    @Test
    fun `rotation returns zero when the key is absent`() {
        assertEquals(0, resolveRotationDegrees(containsRotation = false, readInt = { 90 }))
    }

    @Test
    fun `rotation passes through the common portrait and landscape hints`() {
        assertEquals(0, resolveRotationDegrees(true) { 0 })
        assertEquals(90, resolveRotationDegrees(true) { 90 })
        assertEquals(180, resolveRotationDegrees(true) { 180 })
        assertEquals(270, resolveRotationDegrees(true) { 270 })
    }

    @Test
    fun `rotation normalizes negative and out-of-range values into 0 until 360`() {
        assertEquals(270, resolveRotationDegrees(true) { -90 })
        assertEquals(90, resolveRotationDegrees(true) { 450 })
        assertEquals(0, resolveRotationDegrees(true) { 360 })
    }

    @Test
    fun `rotation returns zero when the read throws`() {
        assertEquals(0, resolveRotationDegrees(true) { throw ClassCastException() })
    }
}
