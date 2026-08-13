package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
