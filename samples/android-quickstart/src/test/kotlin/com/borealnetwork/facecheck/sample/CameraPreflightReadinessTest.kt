package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraPreflightReadinessTest {

    @Test
    fun `stable face enables start only after three continuous seconds`() {
        val readiness = CameraPreflightReadiness(requiredHoldMs = 3_000)

        assertFalse(readiness.onFrame(goodFrame(atMs = 0)).isReady)
        assertFalse(readiness.onFrame(goodFrame(atMs = 2_999)).isReady)
        assertTrue(readiness.onFrame(goodFrame(atMs = 3_000)).isReady)
    }

    @Test
    fun `leaving the guide disables an already ready start button`() {
        val readiness = CameraPreflightReadiness(requiredHoldMs = 3_000)
        readiness.onFrame(goodFrame(atMs = 0))
        assertTrue(readiness.onFrame(goodFrame(atMs = 3_000)).isReady)

        assertFalse(readiness.onFrame(goodFrame(atMs = 3_100, insideGuide = false)).isReady)
        assertFalse(readiness.onFrame(goodFrame(atMs = 6_000)).isReady)
    }

    @Test
    fun `non frontal face cannot start preflight`() {
        val readiness = CameraPreflightReadiness(requiredHoldMs = 3_000)

        assertFalse(readiness.onFrame(goodFrame(atMs = 0, yaw = 18f)).isReady)
        assertFalse(readiness.onFrame(goodFrame(atMs = 3_000, yaw = 18f)).isReady)
    }

    @Test
    fun `outside guide explains that the face must be centered`() {
        val readiness = CameraPreflightReadiness(requiredHoldMs = 3_000)

        val state = readiness.onFrame(goodFrame(atMs = 0, insideGuide = false))

        assertFalse(state.isReady)
        assertTrue(state.instruction.contains("Centra"))
    }

    @Test
    fun `non frontal face asks the user to look straight ahead`() {
        val readiness = CameraPreflightReadiness(requiredHoldMs = 3_000)

        val state = readiness.onFrame(goodFrame(atMs = 0, yaw = 18f))

        assertFalse(state.isReady)
        assertTrue(state.instruction.contains("Mira al frente"))
    }

    private fun goodFrame(
        atMs: Long,
        insideGuide: Boolean = true,
        yaw: Float = 0f,
        faceCount: Int = 1,
    ): FaceFrame = FaceFrame(
        yaw = yaw,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = .5f,
        trackingId = 1,
        timestampMs = atMs,
        faceCount = faceCount,
        insideGuide = insideGuide,
    )
}
