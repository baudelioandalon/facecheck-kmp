package com.borealnetwork.facecheck.sample

import android.graphics.RectF
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceGuideGeometryTest {

    private val guide = FaceGuideGeometry().apply {
        updateForViewport(width = 1_000, height = 1_600)
    }

    @Test
    fun `fully contained face passes the oval gate`() {
        val previewGuide = PreviewFaceGuide(guide::contains)

        assertTrue(previewGuide.contains(rect(left = 450f, top = 600f, right = 550f, bottom = 800f)))
    }

    @Test
    fun `face touching an oval edge is rejected`() {
        assertFalse(guide.contains(rect(left = 120f, top = 742f, right = 160f, bottom = 762f)))
    }

    @Test
    fun `face centered in the oval fails when a corner escapes it`() {
        assertFalse(guide.contains(rect(left = 250f, top = 300f, right = 750f, bottom = 1_184f)))
    }

    @Test
    fun `guide blocks containment until preview geometry is available`() {
        val unavailableGuide = FaceGuideGeometry()

        assertFalse(unavailableGuide.contains(rect(left = 450f, top = 600f, right = 550f, bottom = 800f)))
        unavailableGuide.updateForViewport(width = 0, height = 1_600)
        assertFalse(unavailableGuide.contains(rect(left = 450f, top = 600f, right = 550f, bottom = 800f)))
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF = RectF().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
