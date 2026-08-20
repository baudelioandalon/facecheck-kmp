package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceGuideLightingTest {

    @Test
    fun `flash button toggles the face guide into visual low light mode`() {
        assertEquals(FaceGuideLighting.LowLight, FaceGuideLighting.Normal.toggle())
        assertEquals(FaceGuideLighting.Normal, FaceGuideLighting.LowLight.toggle())
    }

    @Test
    fun `flash button uses only an icon as its visible label`() {
        assertEquals("⚡", FaceGuideLighting.Normal.buttonLabel)
        assertEquals("⚡", FaceGuideLighting.LowLight.buttonLabel)
    }

    @Test
    fun `low light mode turns the area outside the oval into an illuminator`() {
        assertEquals(false, FaceGuideLighting.Normal.illuminatesOutsideGuide)
        assertEquals(true, FaceGuideLighting.LowLight.illuminatesOutsideGuide)
    }

    @Test
    fun `low light mode uses dark button text over the white illuminator`() {
        assertEquals(false, FaceGuideLighting.Normal.requiresDarkButtonText)
        assertEquals(true, FaceGuideLighting.LowLight.requiresDarkButtonText)
    }
}
