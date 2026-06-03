package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReverseScratchJanitorTest {

    @Test
    fun `isDeletableIntermediate matches pass-1 prefix only`() {
        assertTrue(ReverseScratchJanitor.isDeletableIntermediate(File("_intermediate_abcd.mp4")))
        assertFalse(ReverseScratchJanitor.isDeletableIntermediate(File("a1b2c3d4e5f6.mp4")))
        assertFalse(ReverseScratchJanitor.isDeletableIntermediate(File("_intermediate_abcd.mkv")))
        assertFalse(ReverseScratchJanitor.isDeletableIntermediate(File("scaled_trim.mp4")))
    }

    @Test
    fun `cleanup deletes intermediates and preserves cache-key outputs`() {
        val dir = File.createTempFile("janitor_", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        val intermediate = File(dir, "_intermediate_test.mp4").apply { writeBytes(ByteArray(100)) }
        val cacheOutput = File(dir, "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef.mp4").apply {
            writeBytes(ByteArray(50))
        }
        val trackedOutside = File.createTempFile("_intermediate_tracked_", ".mp4").apply {
            writeBytes(ByteArray(80))
        }

        val result = ReverseScratchJanitor.cleanup(
            scratchDir = dir,
            trackedPaths = listOf(trackedOutside.absolutePath),
        )

        assertEquals(2, result.deletedCount)
        assertEquals(180L, result.bytesDeleted)
        assertFalse(intermediate.exists())
        assertFalse(trackedOutside.exists())
        assertTrue("sha1 cache output must remain", cacheOutput.exists())
    }

    @Test
    fun `cleanup on missing directory is a no-op`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "nonexistent-janitor-${System.nanoTime()}")
        val result = ReverseScratchJanitor.cleanup(missing)
        assertEquals(0, result.deletedCount)
        assertEquals(0L, result.bytesDeleted)
    }
}
