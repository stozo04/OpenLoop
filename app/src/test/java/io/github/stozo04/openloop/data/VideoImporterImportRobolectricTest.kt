package io.github.stozo04.openloop.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/**
 * Device-free coverage of [VideoImporterImpl.importToFile] — the stream-copy contract the ViewModel
 * relies on to show a snackbar instead of crashing when a picked URI is unreadable.
 */
@RunWith(RobolectricTestRunner::class)
class VideoImporterImportRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun importToFile_copiesRegisteredContentUri() = runTest {
        val uri = Uri.parse("content://test/import.mp4")
        val payload = "fake-mp4-bytes".toByteArray()
        shadowContentResolver().registerInputStreamSupplier(uri) {
            ByteArrayInputStream(payload)
        }

        val dest = File(context.cacheDir, "imported.mp4")
        val importer = VideoImporterImpl(context)

        assertTrue(importer.importToFile(uri, dest))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun importToFile_returnsFalseWhenSecurityExceptionOnRead() = runTest {
        val uri = Uri.parse("content://test/revoked.mp4")
        shadowContentResolver().registerInputStream(
            uri,
            object : InputStream() {
                override fun read(): Int = throw SecurityException("URI permission revoked")
            },
        )

        val dest = File(context.cacheDir, "imported-revoked.mp4")
        val importer = VideoImporterImpl(context)

        assertFalse(importer.importToFile(uri, dest))
    }

    @Test
    fun importToFile_returnsFalseWhenFileNotFound() = runTest {
        val uri = Uri.fromFile(File(context.cacheDir, "missing-import-source.mp4"))
        val dest = File(context.cacheDir, "imported-missing.mp4")
        val importer = VideoImporterImpl(context)

        assertFalse(importer.importToFile(uri, dest))
    }

    @Test
    fun importToFile_returnsFalseWhenStreamThrowsOnRead() = runTest {
        val uri = Uri.parse("content://test/corrupt.mp4")
        shadowContentResolver().registerInputStream(
            uri,
            object : InputStream() {
                override fun read(): Int = throw java.io.IOException("simulated revoked URI")
            },
        )

        val dest = File(context.cacheDir, "imported-corrupt.mp4")
        val importer = VideoImporterImpl(context)

        assertFalse(importer.importToFile(uri, dest))
    }

    private fun shadowContentResolver(): ShadowContentResolver =
        Shadows.shadowOf(context.contentResolver)
}
