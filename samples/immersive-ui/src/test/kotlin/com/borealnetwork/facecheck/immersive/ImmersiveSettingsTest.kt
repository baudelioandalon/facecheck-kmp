package com.borealnetwork.facecheck.immersive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveSettingsTest {

    @Test
    fun complete_settings_are_ready_after_trimming_values() {
        val settings = ImmersiveSettings(
            baseUrl = " https://us-central1-facecheck-mx.cloudfunctions.net/ ",
            apiKey = " lk_test_a1b2c3d4e5f6g7h8 ",
            subjectId = " persona_demo_01 ",
        )

        assertTrue(settings.isComplete)
        assertTrue(settings.baseUrl == "https://us-central1-facecheck-mx.cloudfunctions.net")
        assertTrue(settings.apiKey == "lk_test_a1b2c3d4e5f6g7h8")
        assertTrue(settings.subjectId == "persona_demo_01")
    }

    @Test
    fun incomplete_settings_are_not_ready() {
        assertFalse(ImmersiveSettings().isComplete)
        assertFalse(ImmersiveSettings(subjectId = "persona_demo_01").isComplete)
    }

    @Test
    fun string_representation_redacts_the_api_key() {
        val key = "lk_test_a1b2c3d4e5f6g7h8"
        val rendered = ImmersiveSettings(apiKey = key, subjectId = "persona_demo_01").toString()

        assertFalse(rendered.contains(key))
        assertTrue(rendered.contains("lk_test_a1b2"))
    }
}
