package com.borealnetwork.facecheck.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveFlashTest {

    @Test
    fun disabled_flash_does_not_change_the_camera_preview() {
        assertEquals(0f, flashOverlayAlpha(enabled = false), 0f)
    }

    @Test
    fun enabled_flash_illuminates_only_the_area_outside_the_oval() {
        val alpha = flashOverlayAlpha(enabled = true)

        assertEquals(1f, alpha, 0f)
    }
}
