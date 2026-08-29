package com.borealnetwork.facecheck.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPropertiesSettingsTest {

    @Test
    fun local_properties_load_connection_and_leave_subject_id_for_the_generator() {
        val settings = LocalPropertiesSettings.from(
            mapOf(
                "FACECHECK_BASE_URL" to " https://us-central1-facecheck-mx.cloudfunctions.net ",
                "FACECHECK_API_KEY" to " lk_test_a1b2c3d4e5f6g7h8 ",
            ),
        )

        assertFalse(settings.isComplete)
        assertEquals("https://us-central1-facecheck-mx.cloudfunctions.net", settings.baseUrl)
        assertEquals("lk_test_a1b2c3d4e5f6g7h8", settings.apiKey)
        assertEquals("", settings.subjectId)
    }

    @Test
    fun absent_local_properties_leave_the_shared_setup_state_incomplete() {
        val settings = LocalPropertiesSettings.from(emptyMap())

        assertFalse(settings.isComplete)
        assertEquals("", settings.apiKey)
    }

    @Test
    fun optional_subject_id_from_local_properties_makes_the_sample_ready_to_run() {
        val settings = LocalPropertiesSettings.from(
            mapOf(
                "FACECHECK_BASE_URL" to "https://us-central1-facecheck-mx.cloudfunctions.net",
                "FACECHECK_API_KEY" to "lk_test_a1b2c3d4e5f6g7h8",
                "FACECHECK_SUBJECT_ID" to " sub_TESTUSER_1234567890 ",
            ),
        )

        assertTrue(settings.isComplete)
        assertEquals("sub_TESTUSER_1234567890", settings.subjectId)
    }

    @Test
    fun redacted_settings_do_not_contain_the_complete_api_key() {
        val settings = LocalPropertiesSettings.from(
            mapOf("FACECHECK_API_KEY" to "lk_test_a1b2c3d4e5f6g7h8"),
        )

        assertFalse(settings.toString().contains("lk_test_a1b2c3d4e5f6g7h8"))
    }
}
