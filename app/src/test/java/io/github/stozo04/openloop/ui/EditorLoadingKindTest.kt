package io.github.stozo04.openloop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLoadingKindTest {
    @Test
    fun isReversePreviewLoading_trueForTrimmingAndLoopifying() {
        assertTrue(EditorLoadingKind.TRIMMING.isReversePreviewLoading())
        assertTrue(EditorLoadingKind.LOOPIFYING.isReversePreviewLoading())
    }

    @Test
    fun isReversePreviewLoading_falseForOtherKindsAndNull() {
        assertFalse(null.isReversePreviewLoading())
        assertFalse(EditorLoadingKind.HOLD_TIGHT.isReversePreviewLoading())
        assertFalse(EditorLoadingKind.FILTERING.isReversePreviewLoading())
    }
}
