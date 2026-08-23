package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.FrameQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentBackCaptureReadinessTest {

    @Test
    fun `back capture waits for stable readable frames before unlocking`() {
        val readiness = DocumentBackCaptureReadiness()

        val dark = readiness.onFrame(frame(timestampMs = 0L, brightness = 30f))
        assertFalse(dark.isReady)
        assertEquals("Busca más luz para leer el reverso", dark.instruction)

        val waiting = readiness.onFrame(frame(timestampMs = 1_000L))
        assertFalse(waiting.isReady)
        assertEquals("Alineando reverso", waiting.stepLabel)

        val ready = readiness.onFrame(frame(timestampMs = DocumentBackCaptureReadiness.STABLE_HOLD_MS + 1_000L))
        assertTrue(ready.isReady)
        assertEquals("Reverso de la INE listo", ready.stepLabel)
    }

    private fun frame(
        timestampMs: Long,
        sharpness: Float = 200f,
        brightness: Float = 120f,
    ): FaceFrame = FaceFrame(
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = 0f,
        trackingId = null,
        timestampMs = timestampMs,
        quality = FrameQuality(
            sharpness = sharpness,
            brightness = brightness,
            detectorScore = 0.99f,
        ),
        faceCount = 0,
        insideGuide = false,
    )
}
