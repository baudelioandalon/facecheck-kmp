package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationPreflightReadinessTest {

    @Test
    fun `enables start only after the face stays inside the guide for one second`() {
        val readiness = VerificationPreflightReadiness()

        assertFalse(readiness.onFrame(frame(atMs = 0, insideGuide = true)).isReady)
        assertFalse(readiness.onFrame(frame(atMs = 999, insideGuide = true)).isReady)
        assertTrue(readiness.onFrame(frame(atMs = 1_000, insideGuide = true)).isReady)
    }

    @Test
    fun `leaving the guide disables an already ready start button`() {
        val readiness = VerificationPreflightReadiness()
        readiness.onFrame(frame(atMs = 0, insideGuide = true))
        assertTrue(readiness.onFrame(frame(atMs = 1_000, insideGuide = true)).isReady)

        assertFalse(readiness.onFrame(frame(atMs = 1_001, insideGuide = false)).isReady)
        assertFalse(readiness.onFrame(frame(atMs = 2_000, insideGuide = true)).isReady)
    }

    private fun frame(atMs: Long, insideGuide: Boolean): FaceFrame = FaceFrame(
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = .5f,
        trackingId = 1,
        timestampMs = atMs,
        insideGuide = insideGuide,
    )
}
