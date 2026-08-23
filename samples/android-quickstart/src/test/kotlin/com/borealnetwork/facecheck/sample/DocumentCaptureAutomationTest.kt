package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCaptureAutomationTest {

    @Test
    fun frontCaptured_waitsForAutomaticBackCapture() {
        val state = DocumentCaptureAutomation.State(frontCaptured = true, backCaptured = false, uploading = false)

        assertTrue(state.shouldAutoCaptureBack)
        assertFalse(state.shouldAutoUpload)
        assertEquals("Paso 2 de 2", state.stepLabel)
    }

    @Test
    fun bothSidesCaptured_uploadsAutomatically() {
        val state = DocumentCaptureAutomation.State(frontCaptured = true, backCaptured = true, uploading = false)

        assertFalse(state.shouldAutoCaptureBack)
        assertTrue(state.shouldAutoUpload)
        assertEquals("Enviando INE", state.stepLabel)
    }
}
