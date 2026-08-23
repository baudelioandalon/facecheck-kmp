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
        assertFalse(dark.hasCardSignal)
        assertEquals("Busca más luz para leer el reverso", dark.instruction)

        val waiting = readiness.onFrame(frame(timestampMs = 1_000L))
        assertFalse(waiting.isReady)
        assertTrue(waiting.hasCardSignal)
        assertEquals("Alineando reverso", waiting.stepLabel)

        val ready = readiness.onFrame(frame(timestampMs = DocumentBackCaptureReadiness.STABLE_HOLD_MS + 1_000L))
        assertTrue(ready.isReady)
        assertTrue(ready.hasCardSignal)
        assertEquals("Reverso de la INE listo", ready.stepLabel)
    }

    @Test
    fun `back capture keeps small false face detections eligible for manual capture`() {
        val readiness = DocumentBackCaptureReadiness()

        val state = readiness.onFrame(frame(timestampMs = 0L, faceCount = 1, faceRatio = 0.08f))

        assertFalse(state.isReady)
        assertTrue(state.hasCardSignal)
        assertEquals("Mantén el reverso dentro del rectángulo 2…", state.instruction)
    }

    @Test
    fun `back capture does not unlock when a front portrait is detected`() {
        val readiness = DocumentBackCaptureReadiness()

        val state = readiness.onFrame(frame(timestampMs = 0L, faceCount = 1, faceRatio = 0.18f))

        assertFalse(state.isReady)
        assertFalse(state.hasCardSignal)
        assertEquals("Ese parece el frente; gira la INE y muestra el reverso", state.instruction)
    }

    private fun frame(
        timestampMs: Long,
        sharpness: Float = 200f,
        brightness: Float = 120f,
        faceCount: Int = 0,
        faceRatio: Float = 0f,
    ): FaceFrame = FaceFrame(
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = faceRatio,
        trackingId = null,
        timestampMs = timestampMs,
        quality = FrameQuality(
            sharpness = sharpness,
            brightness = brightness,
            detectorScore = 0.99f,
        ),
        faceCount = faceCount,
        insideGuide = false,
    )
}
