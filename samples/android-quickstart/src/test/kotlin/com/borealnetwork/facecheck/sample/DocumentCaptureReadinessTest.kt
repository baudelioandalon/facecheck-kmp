package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.FrameQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCaptureReadinessTest {

    @Test
    fun `front capture only unlocks after a stable face inside the rectangle`() {
        val readiness = DocumentCaptureReadiness()

        val blocked = readiness.onFrame(
            frame(
                faceCount = 1,
                insideGuide = false,
                timestampMs = 0L,
            ),
        )
        assertFalse(blocked.isReady)
        assertEquals("Centra la INE dentro del rectángulo", blocked.instruction)

        val waiting = readiness.onFrame(
            frame(
                faceCount = 1,
                insideGuide = true,
                timestampMs = 1_000L,
            ),
        )
        assertFalse(waiting.isReady)
        assertEquals("Alineando INE", waiting.stepLabel)

        val ready = readiness.onFrame(
            frame(
                faceCount = 1,
                insideGuide = true,
                timestampMs = DocumentCaptureReadiness.STABLE_HOLD_MS + 1_000L,
            ),
        )
        assertTrue(ready.isReady)
        assertEquals("Frente de la INE lista", ready.stepLabel)
    }

    private fun frame(
        faceCount: Int,
        insideGuide: Boolean,
        timestampMs: Long,
    ): FaceFrame = FaceFrame(
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = 0.42f,
        trackingId = 1,
        timestampMs = timestampMs,
        quality = FrameQuality(
            sharpness = 200f,
            brightness = 120f,
            detectorScore = 0.99f,
        ),
        faceCount = faceCount,
        insideGuide = insideGuide,
    )
}
