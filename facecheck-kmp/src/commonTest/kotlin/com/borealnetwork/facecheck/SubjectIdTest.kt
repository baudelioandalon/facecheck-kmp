package com.borealnetwork.facecheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubjectIdTest {

    @Test
    fun generated_subject_id_has_the_expected_format_and_changes_per_call() {
        val first = SubjectId.generate("lk_test_example")
        val second = SubjectId.generate("lk_test_example")

        assertTrue(GENERATED_SUBJECT_ID_PATTERN.matches(first))
        assertTrue(GENERATED_SUBJECT_ID_PATTERN.matches(second))
        assertEquals(first.fingerprintPrefix(), second.fingerprintPrefix())
        assertNotEquals(first, second)
    }

    @Test
    fun subject_id_validation_accepts_only_the_server_format() {
        assertTrue(isValidSubjectId("sub_ABCDEFGHIJ_abcdefghijklmnopqrstuv"))
        assertTrue(isValidSubjectId("person_demo_01"))
        assertFalse(isValidSubjectId("sub_ABCDEFGHIJ_abcdefghijklmnopqrstu="))
        assertFalse(isValidSubjectId("bad id"))
    }
}

private fun String.fingerprintPrefix(): String = substring(0, indexOf('_', startIndex = "sub_".length))
