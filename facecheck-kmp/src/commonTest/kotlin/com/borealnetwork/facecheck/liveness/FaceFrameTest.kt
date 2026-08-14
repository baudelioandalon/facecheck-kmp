package com.borealnetwork.facecheck.liveness

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FaceFrameTest {

    @Test
    fun `normalized bounds reject an inverted horizontal edge`() {
        assertFailsWith<IllegalArgumentException> {
            NormalizedFaceBounds(left = .7f, top = .2f, right = .3f, bottom = .8f)
        }
    }

    @Test
    fun `fixture can mark a detected face outside the guide`() {
        assertFalse(frame(atMs = 0, insideGuide = false).insideGuide)
    }
}
