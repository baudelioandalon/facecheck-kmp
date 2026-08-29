package com.borealnetwork.facecheck.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveFlowStateTest {

    @Test
    fun readiness_requires_three_seconds_and_exposes_a_visible_countdown() {
        assertEquals(3_000L, ImmersiveFlowReducer.READY_HOLD_MS)
        assertEquals(3, stableCountdownSeconds(1L))
        assertEquals(2, stableCountdownSeconds(1_001L))
        assertEquals(1, stableCountdownSeconds(2_001L))
        assertNull(stableCountdownSeconds(3_000L))
    }

    @Test
    fun stability_progress_tracks_the_three_second_gate() {
        assertEquals(0f, stableProgress(0L))
        assertEquals(0.5f, stableProgress(1_500L))
        assertEquals(1f, stableProgress(3_000L))
        assertEquals(1f, stableProgress(4_000L))
    }

    @Test
    fun start_requires_one_stable_face_inside_the_oval() {
        val reducer = ImmersiveFlowReducer()

        val outside = reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = false,
                stableForMs = 1_500L,
            ),
        )
        assertEquals(ImmersiveFlowState.Positioning, outside)
        assertEquals(ImmersiveFlowState.Positioning, reducer.reduce(ImmersiveFlowEvent.StartPressed))

        val ready = reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = true,
                stableForMs = ImmersiveFlowReducer.READY_HOLD_MS,
            ),
        )
        assertEquals(ImmersiveFlowState.Ready, ready)
        assertTrue(reducer.reduce(ImmersiveFlowEvent.StartPressed) is ImmersiveFlowState.Running)
    }

    @Test
    fun face_outside_the_oval_never_becomes_ready_even_after_three_seconds() {
        val reducer = ImmersiveFlowReducer()

        val state = reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = false,
                stableForMs = ImmersiveFlowReducer.READY_HOLD_MS,
            ),
        )

        assertEquals(ImmersiveFlowState.Positioning, state)
        assertEquals(ImmersiveFlowState.Positioning, reducer.reduce(ImmersiveFlowEvent.StartPressed))
    }

    @Test
    fun challenge_completion_advances_left_right_front_then_saves() {
        val reducer = ImmersiveFlowReducer()
        reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = true,
                stableForMs = ImmersiveFlowReducer.READY_HOLD_MS,
            ),
        )
        reducer.reduce(ImmersiveFlowEvent.StartPressed)

        assertEquals(1, (reducer.reduce(ImmersiveFlowEvent.ChallengeCompleted) as ImmersiveFlowState.Running).step)
        assertEquals(2, (reducer.reduce(ImmersiveFlowEvent.ChallengeCompleted) as ImmersiveFlowState.Running).step)
        assertEquals(ImmersiveFlowState.Saving, reducer.reduce(ImmersiveFlowEvent.ChallengeCompleted))
    }

    @Test
    fun operation_error_returns_to_positioning_without_manual_retry() {
        val reducer = ImmersiveFlowReducer()
        reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = true,
                stableForMs = ImmersiveFlowReducer.READY_HOLD_MS,
            ),
        )
        reducer.reduce(ImmersiveFlowEvent.StartPressed)
        reducer.reduce(ImmersiveFlowEvent.ErrorRaised)
        assertEquals(ImmersiveFlowState.Positioning, reducer.state)

        val readyAgain = reducer.reduce(
            ImmersiveFlowEvent.FaceFrameUpdated(
                faceCount = 1,
                insideOval = true,
                stableForMs = ImmersiveFlowReducer.READY_HOLD_MS,
            ),
        )
        assertEquals(ImmersiveFlowState.Ready, readyAgain)

        reducer.reduce(ImmersiveFlowEvent.Completed)
        assertFalse(reducer.reduce(ImmersiveFlowEvent.StartPressed) is ImmersiveFlowState.Running)
    }
}
