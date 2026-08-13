package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import com.borealnetwork.facecheck.liveness.ChallengePhase
import com.borealnetwork.facecheck.liveness.LivenessState
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals("Reto 2 de 3", presentation.stepLabel)
        assertEquals(1f / 3f, presentation.ringProgress)
    }

    @Test
    fun `ring progress is clamped for the guide overlay`() {
        assertEquals(
            1f,
            CapturePresentation(instruction = "Listo", stepLabel = "Completado", progress = 2f).ringProgress,
        )
    }
}
