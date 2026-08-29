package com.borealnetwork.facecheck.immersive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveOvalGeometryTest {

    @Test
    fun face_box_inside_portrait_preview_oval_is_accepted() {
        assertTrue(
            isFaceBoxInsideOval(
                centerX = 0.5f,
                centerY = 0.36f,
                width = 0.18f,
                height = 0.28f,
                imageAspectRatio = 0.75f,
                previewAspectRatio = 0.45f,
            ),
        )
    }

    @Test
    fun face_box_outside_horizontal_oval_is_rejected() {
        assertFalse(
            isFaceBoxInsideOval(
                centerX = 0.85f,
                centerY = 0.36f,
                width = 0.14f,
                height = 0.22f,
                imageAspectRatio = 0.75f,
                previewAspectRatio = 0.45f,
            ),
        )
    }

    @Test
    fun face_box_outside_vertical_oval_is_rejected() {
        assertFalse(
            isFaceBoxInsideOval(
                centerX = 0.5f,
                centerY = 0.76f,
                width = 0.14f,
                height = 0.16f,
                imageAspectRatio = 0.75f,
                previewAspectRatio = 0.45f,
            ),
        )
    }

    @Test
    fun ml_kit_padding_does_not_reject_face_core_inside_oval() {
        assertTrue(
            isFaceBoxInsideOval(
                centerX = 0.485f,
                centerY = 0.420f,
                width = 0.432f,
                height = 0.325f,
                imageAspectRatio = 0.75f,
                previewAspectRatio = 0.4736842f,
            ),
        )
    }

    @Test
    fun face_core_outside_oval_is_still_rejected() {
        assertFalse(
            isFaceBoxInsideOval(
                centerX = 0.80f,
                centerY = 0.42f,
                width = 0.34f,
                height = 0.30f,
                imageAspectRatio = 0.75f,
                previewAspectRatio = 0.4736842f,
            ),
        )
    }
}
