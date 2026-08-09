package io.github.stozo04.openloop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pure JVM coverage for the share-sheet MIME decision (docs/PRD-photo-capture.md §5.6).
 *
 * Before photo mode, `buildBoomerangShareIntent` hard-coded `video/mp4`. Advertising that for a JPEG
 * hides the photo from image-only share targets and offers it to video-only ones, so the branch is
 * worth locking down — and it is pure `File` string handling, so it needs no Android framework.
 */
class ShareMimeTypeTest {

    private fun videosFile(name: String) = File("/data/user/0/app/files/videos/$name")

    @Test
    fun `photo-mode stills share as jpeg`() {
        assertEquals("image/jpeg", shareMimeType(videosFile("photo_1716825600000.jpg")))
    }

    @Test
    fun `rendered loops and raw clips share as mp4`() {
        assertEquals("video/mp4", shareMimeType(videosFile("boom_200_from_100.mp4")))
        assertEquals("video/mp4", shareMimeType(videosFile("clip_100.mp4")))
    }
}
