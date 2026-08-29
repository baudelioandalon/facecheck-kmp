package com.borealnetwork.facecheck.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.PositioningHint

class ImmersiveEnrollmentUiTest {

    @Test
    fun enrollment_screen_exposes_only_the_primary_action() {
        val controls = enrollmentControls(enabled = true, busy = false)

        assertEquals("Empezar", controls.primaryLabel)
        assertTrue(controls.showPrimaryAction)
        assertFalse(controls.showVerificationAction)
        assertFalse(controls.showOverwriteToggle)
        assertFalse(controls.showTestCapture)
        assertFalse(controls.showSettingsAction)
        assertFalse(controls.showSettingsAction)
    }

    @Test
    fun enrollment_instruction_does_not_call_the_flow_verification() {
        val instruction = enrollmentInstruction(
            com.borealnetwork.facecheck.liveness.LivenessState.Idle,
        )

        assertEquals("Prepárate para el enrolamiento", instruction)
    }

    @Test
    fun framing_warns_to_move_away_when_face_is_too_close() {
        val hint = framingHintFor(
            FaceFrame(
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                leftEyeOpen = 1f,
                rightEyeOpen = 1f,
                faceRatio = 0.95f,
                trackingId = 7,
                timestampMs = 1L,
                faceCount = 1,
            ),
        )

        assertEquals(PositioningHint.MOVE_AWAY, hint)
    }

    @Test
    fun framing_does_not_warn_to_move_away_for_a_normal_face_size() {
        val hint = framingHintFor(
            FaceFrame(
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                leftEyeOpen = 1f,
                rightEyeOpen = 1f,
                faceRatio = 0.45f,
                trackingId = 7,
                timestampMs = 1L,
                faceCount = 1,
            ),
        )

        assertEquals(null, hint)
    }

    @Test
    fun framing_explains_that_the_user_must_look_straight() {
        val hint = framingHintFor(
            FaceFrame(
                yaw = 20f,
                pitch = 0f,
                roll = 0f,
                leftEyeOpen = 1f,
                rightEyeOpen = 1f,
                faceRatio = 0.35f,
                trackingId = 7,
                timestampMs = 1L,
                faceCount = 1,
            ),
            previewAspectRatio = 0.45f,
        )

        assertEquals(PositioningHint.LOOK_STRAIGHT, hint)
    }

    @Test
    fun framing_explains_that_the_face_must_be_inside_the_oval() {
        val hint = framingHintFor(
            FaceFrame(
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                leftEyeOpen = 1f,
                rightEyeOpen = 1f,
                faceRatio = 0.30f,
                trackingId = 7,
                timestampMs = 1L,
                faceCount = 1,
                insideGuide = false,
            ),
            previewAspectRatio = 0.45f,
        )

        assertEquals(PositioningHint.OUTSIDE_GUIDE, hint)
    }
}
