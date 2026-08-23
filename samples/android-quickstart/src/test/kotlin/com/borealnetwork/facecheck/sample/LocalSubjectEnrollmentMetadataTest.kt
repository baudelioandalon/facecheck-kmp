package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSubjectEnrollmentMetadataTest {

    @Test
    fun `metadata reads dates and keeps legacy subjects as unknown`() {
        val records = LocalSubjectEnrollmentMetadata.read(
            storedMetadata = """
                sub_new_123|1787500000000
                invalid
                sub_bad|not-a-number
            """.trimIndent(),
            legacySubjects = listOf("sub_old_123", "sub_new_123"),
        )

        assertEquals(
            listOf(
                SubjectEnrollmentRecord("sub_new_123", 1787500000000),
                SubjectEnrollmentRecord("sub_old_123", null),
            ),
            records,
        )
    }

    @Test
    fun `remember upserts subject at the top with enrollment date`() {
        val records = LocalSubjectEnrollmentMetadata.remember(
            existing = listOf(
                SubjectEnrollmentRecord("sub_old_123", 1700000000000),
            ),
            subjectId = " sub_new_123 ",
            enrolledAtMs = 1787500000000,
        )

        assertEquals(
            listOf(
                SubjectEnrollmentRecord("sub_new_123", 1787500000000),
                SubjectEnrollmentRecord("sub_old_123", 1700000000000),
            ),
            records,
        )
        assertEquals("sub_new_123|1787500000000\nsub_old_123|1700000000000", LocalSubjectEnrollmentMetadata.serialize(records))
    }

    @Test
    fun `forget removes metadata for a subject`() {
        val records = LocalSubjectEnrollmentMetadata.forget(
            existing = listOf(
                SubjectEnrollmentRecord("sub_a_12345", 1000),
                SubjectEnrollmentRecord("sub_b_12345", null),
            ),
            subjectId = "sub_a_12345",
        )

        assertEquals(listOf(SubjectEnrollmentRecord("sub_b_12345", null)), records)
    }
}
