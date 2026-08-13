package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import com.borealnetwork.facecheck.liveness.ChallengePhase
import com.borealnetwork.facecheck.liveness.LivenessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapturePresentationTest {

    @Test
    fun `active liveness challenge exposes the current visual step`() {
        val presentation = CapturePresentation.from(
            LivenessState.ChallengeActive(
                challenge = Challenge.TurnLeft,
                index = 1,
                total = 3,
                phase = ChallengePhase.AWAITING_ACTION,
            ),
        )

        assertEquals("Gira la cabeza a la izquierda", presentation.instruction)
        assertEquals("Paso 2 de 3", presentation.stepLabel)
        assertEquals(1f / 3f, presentation.ringProgress)
    }

    @Test
    fun `ring progress is clamped for the guide overlay`() {
        assertEquals(
            1f,
            CapturePresentation(instruction = "Listo", stepLabel = "Completado", progress = 2f).ringProgress,
        )
    }

    @Test
    fun `capture state presents a loading handoff after the three steps`() {
        val presentation = CapturePresentation.from(LivenessState.Capturing)

        assertEquals("Guardando enrolamiento…", presentation.instruction)
        assertEquals("Pasos completados", presentation.stepLabel)
        assertTrue(presentation.isFinalizing)
    }
}
