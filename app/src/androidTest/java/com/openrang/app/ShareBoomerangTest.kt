package com.openrang.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Slice 06 share-sheet guards. Two independent concerns:
 *
 * 1. **FileProvider scope** — the provider in AndroidManifest + res/xml/file_paths.xml must expose
 *    ONLY `filesDir/boomerangs/`. A rendered boomerang resolves to a `content://` URI; a raw capture
 *    in `filesDir/videos/` must NOT (so raws can never leak through the share sheet). This is the
 *    acceptance-criterion "confirm a raw path is not shareable through getUriForFile".
 * 2. **Share intent shape** — [buildBoomerangShareIntent] must produce an ACTION_SEND `video/mp4`
 *    intent carrying the URI as EXTRA_STREAM, the subject, and the temporary read-grant flag.
 *
 * Instrumented (not JVM) because both use the real FileProvider / Intent on a device.
 */
@RunWith(AndroidJUnit4::class)
class ShareBoomerangTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority: String get() = "${context.packageName}.fileprovider"

    @Test
    fun fileProvider_exposesBoomerangFile_asContentUri() {
        val boomerangs = File(context.filesDir, "boomerangs").apply { mkdirs() }
        val boomFile = File(boomerangs, "boom_test_from_1.mp4").apply { writeBytes(ByteArray(4)) }
        try {
            val uri = FileProvider.getUriForFile(context, authority, boomFile)
            assertNotNull(uri)
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
        } finally {
            boomFile.delete()
        }
    }

    @Test
    fun fileProvider_rejectsRawCapture_notInConfiguredPaths() {
        // Raws live in filesDir/videos/ — deliberately NOT listed in file_paths.xml, so getUriForFile
        // must refuse to mint a URI for them (only boomerangs/ is shareable).
        val videos = File(context.filesDir, "videos").apply { mkdirs() }
        val rawFile = File(videos, "clip_test.mp4").apply { writeBytes(ByteArray(4)) }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, rawFile)
            }
        } finally {
            rawFile.delete()
        }
    }

    @Test
    fun buildBoomerangShareIntent_hasSendActionTypeStreamSubjectAndReadGrant() {
        val uri = Uri.parse("content://$authority/boomerangs/boom_test_from_1.mp4")

        val intent = buildBoomerangShareIntent(uri, "OpenRang boomerang")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("OpenRang boomerang", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(
            "ACTION_SEND must grant temporary read access to the receiver",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }
}
