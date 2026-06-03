package io.github.stozo04.openloop.ui

import io.github.stozo04.openloop.media.VideoFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEffectsPreviewTest {

    @Test
    fun `ORIGINAL never requests video effects`() {
        assertFalse(shouldApplyVideoEffectsPreview(effectsPreviewEnabled = true, filter = VideoFilter.ORIGINAL))
        assertFalse(shouldApplyVideoEffectsPreview(effectsPreviewEnabled = false, filter = VideoFilter.ORIGINAL))
    }

    @Test
    fun `non-original looks require effects preview enabled`() {
        assertTrue(
            shouldApplyVideoEffectsPreview(effectsPreviewEnabled = true, filter = VideoFilter.POP),
        )
        assertFalse(
            shouldApplyVideoEffectsPreview(effectsPreviewEnabled = false, filter = VideoFilter.POP),
        )
    }

    // ── shouldTearDownEffectsPlayer (PR #58 review: applied effects survive rebinds; the player
    //    must be recreated when the gate closes on them) ─────────────────────────────────────────

    @Test
    fun `teardown required when applied effects are no longer allowed`() {
        // Gate closed (reverse failure / memory pressure) with a look still attached to the player.
        assertTrue(
            shouldTearDownEffectsPlayer(
                playerHasAppliedEffects = true,
                effectsPreviewEnabled = false,
                filter = VideoFilter.POP,
            ),
        )
        // Back to ORIGINAL with a look attached: the no-empty-list rule means recreation is the
        // only way the preview can actually return to the unfiltered clip.
        assertTrue(
            shouldTearDownEffectsPlayer(
                playerHasAppliedEffects = true,
                effectsPreviewEnabled = true,
                filter = VideoFilter.ORIGINAL,
            ),
        )
    }

    @Test
    fun `no teardown when nothing was applied`() {
        // Closing the gate on a clean player must NOT recreate it (no effects to drop) — this is
        // what prevents an epoch-bump loop after the recreated instance composes.
        assertFalse(
            shouldTearDownEffectsPlayer(
                playerHasAppliedEffects = false,
                effectsPreviewEnabled = false,
                filter = VideoFilter.POP,
            ),
        )
        assertFalse(
            shouldTearDownEffectsPlayer(
                playerHasAppliedEffects = false,
                effectsPreviewEnabled = true,
                filter = VideoFilter.ORIGINAL,
            ),
        )
    }

    @Test
    fun `no teardown while an allowed look is playing`() {
        assertFalse(
            shouldTearDownEffectsPlayer(
                playerHasAppliedEffects = true,
                effectsPreviewEnabled = true,
                filter = VideoFilter.POP,
            ),
        )
    }
}
