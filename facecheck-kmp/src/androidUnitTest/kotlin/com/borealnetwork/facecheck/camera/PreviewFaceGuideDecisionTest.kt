package com.borealnetwork.facecheck.camera

import android.graphics.RectF
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreviewFaceGuideDecisionTest {

    @Test
    fun `unavailable preview transform blocks the guide`() {
        assertFalse(guideContainsMappedFace(mappedFaceBounds = null) { true })
    }

    @Test
    fun `mapped preview bounds are passed unchanged to the host guide`() {
        val mappedFaceBounds = rect(left = 450f, top = 600f, right = 550f, bottom = 800f)
        var received: RectF? = null

        val accepted = guideContainsMappedFace(mappedFaceBounds) { bounds ->
            received = bounds
            bounds.left == 450f && bounds.top == 600f && bounds.right == 550f && bounds.bottom == 800f
        }

        assertTrue(accepted)
        assertSame(mappedFaceBounds, received)
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF = RectF().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
