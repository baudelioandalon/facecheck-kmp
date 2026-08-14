package com.borealnetwork.facecheck.liveness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceFrameTest {

    @Test
    fun `normalized bounds reject an inverted horizontal edge`() {
        assertFailsWith<IllegalArgumentException> {
            NormalizedFaceBounds(left = .7f, top = .2f, right = .3f, bottom = .8f)
        }
    }

    @Test
    fun `fixture defaults to one scorable face inside an unbounded guide`() {
        val candidate = frame(atMs = 0)

        assertEquals(1, candidate.faceCount)
        assertEquals(.40f, candidate.faceRatio)
        assertEquals(1, candidate.trackingId)
        assertNull(candidate.bounds)
        assertTrue(candidate.insideGuide)
    }

    @Test
    fun `historical source constructor defaults guide fields`() {
        val candidate = FaceFrame(
            yaw = 1f,
            pitch = 2f,
            roll = 3f,
            leftEyeOpen = .9f,
            rightEyeOpen = .8f,
            faceRatio = .4f,
            trackingId = 7,
            timestampMs = 42L,
            quality = FrameQuality(sharpness = 111f, brightness = 122f, detectorScore = .8f),
            faceCount = 1,
        )

        assertNull(candidate.bounds)
        assertTrue(candidate.insideGuide)
    }
}
