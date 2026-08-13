package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImmersiveSampleFlowTest {

    @Test
    fun `valid trimmed email starts enrollment capture`() {
        assertEquals(
            ImmersiveScreen.Capture(SampleOperation.ENROLL, "ana@example.com"),
            ImmersiveSampleFlow.begin(SampleOperation.ENROLL, "  ana@example.com  "),
        )
    }

    @Test
    fun `invalid email stays in subject setup with guidance`() {
        assertEquals(
            ImmersiveScreen.SubjectSetup(SampleOperation.VERIFY, "Escribe un correo válido."),
            ImmersiveSampleFlow.begin(SampleOperation.VERIFY, "sin-correo"),
        )
    }

    @Test
    fun `remembered subjects are normalized deduplicated and newest first`() {
        assertEquals(
            listOf("new@example.com", "old@example.com"),
            LocalSubjectDirectory.remember(
                existing = listOf("old@example.com", "new@example.com"),
                successfulEnrollment = " NEW@example.com ",
            ),
        )
    }

    @Test
    fun `enrollment always asks left then right then front`() {
        assertEquals(
            listOf(Challenge.TurnLeft, Challenge.TurnRight, Challenge.Center),
            EnrollmentSessionPolicy.challenges,
        )
    }

    @Test
    fun `enrollment allows two retries after the first attempt`() {
        assertEquals("Intento 1 de 3", EnrollmentAttempt.first.label)
        assertEquals("Intento 2 de 3", EnrollmentAttempt.first.retry()?.label)
        assertEquals("Intento 3 de 3", EnrollmentAttempt.first.retry()?.retry()?.label)
        assertNull(EnrollmentAttempt.first.retry()?.retry()?.retry())
    }

    @Test
    fun `environment badge comes from the API key prefix without exposing it`() {
        assertEquals("TEST", SampleEnvironment.fromApiKey("lk_test_anything").label)
        assertEquals("PRODUCTION", SampleEnvironment.fromApiKey("lk_live_anything").label)
    }
}
