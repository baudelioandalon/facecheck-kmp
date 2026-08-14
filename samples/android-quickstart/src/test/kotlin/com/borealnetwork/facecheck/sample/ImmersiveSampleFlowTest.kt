package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImmersiveSampleFlowTest {

    @Test
    fun `generated subject ID starts enrollment capture`() {
        assertEquals(
            ImmersiveScreen.Capture(
                SampleOperation.ENROLL,
                "sub_ABCDEFGHJK_abcdefghijklmnopqrstuv",
            ),
            ImmersiveSampleFlow.begin(
                SampleOperation.ENROLL,
                "  sub_ABCDEFGHJK_abcdefghijklmnopqrstuv  ",
            ),
        )
    }

    @Test
    fun `verification begins with a preflight instead of a live session`() {
        assertEquals(
            ImmersiveScreen.VerificationPreflight("Person_01"),
            ImmersiveSampleFlow.begin(SampleOperation.VERIFY, " Person_01 "),
        )
    }

    @Test
    fun `invalid subject ID stays in setup with guidance`() {
        assertEquals(
            ImmersiveScreen.SubjectSetup(
                operation = SampleOperation.VERIFY,
                validationMessage = "Escribe un ID de persona válido.",
                subjectId = "sin espacios",
            ),
            ImmersiveSampleFlow.begin(SampleOperation.VERIFY, "sin espacios"),
        )
    }

    @Test
    fun `successful enrollment directory keeps subject IDs distinct and newest first`() {
        assertEquals(
            listOf("Person_02", "Person_01"),
            LocalSubjectDirectory.remember(
                existing = listOf("Person_01", "Person_02"),
                successfulEnrollment = " Person_02 ",
            ),
        )
    }

    @Test
    fun `verification directory excludes values that are not subject IDs`() {
        assertEquals(
            listOf("Person_01"),
            LocalSubjectDirectory.normalizedDistinct(listOf("Person_01", "valor no válido")),
        )
    }

    @Test
    fun `reading legacy stored subjects persists only valid subject IDs`() {
        var storedSubjects = "legacy@example.invalid\nPerson_01\nPerson_01"

        val knownSubjects = LocalSubjectDirectory.readAndMigrate(
            readStoredSubjects = { storedSubjects },
            persistSubjects = { storedSubjects = it },
        )

        assertEquals(listOf("Person_01"), knownSubjects)
        assertEquals("Person_01", storedSubjects)
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

    @Test
    fun `retry result hides the finalizing loading layer`() {
        assertEquals(false, EnrollmentRetryPresentation.from(EnrollmentAttempt.first).showsLoading)
    }
}
