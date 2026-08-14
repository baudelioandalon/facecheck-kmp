package com.borealnetwork.facecheck.liveness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The machine is pure and clock-free, so every one of these drives a full
 * session — twenty seconds of wall time, several times over — in microseconds.
 */
class ChallengeMachineTest {

    private fun machine(vararg challenges: Challenge) =
        ChallengeMachine(challenges.toList(), testConfig)

    // --- Happy paths ----------------------------------------------------------

    @Test
    fun completes_a_two_challenge_session() {
        val machine = machine(Challenge.TurnLeft, Challenge.TurnRight)

        val startedAt = machine.completePositioning()
        val afterLeft = machine.performTurn(startedAt, yawAtExtreme = -35f)
        machine.performTurn(afterLeft, yawAtExtreme = 35f)

        assertEquals(LivenessState.Capturing, machine.state.value)

        machine.completeCapture()
        val done = assertIs<LivenessState.Done>(machine.state.value)
        assertEquals(
            listOf(Challenge.TurnLeft, Challenge.TurnRight),
            done.evidence.challenges.map { it.challenge },
        )
        assertNotNull(done.evidence.primary)
    }

    @Test
    fun idle_until_the_first_frame_then_positioning() {
        val machine = machine(Challenge.TurnLeft)
        assertEquals(LivenessState.Idle, machine.state.value)

        machine.onFrame(frame(atMs = 0))

        val state = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.OK, state.hint)
    }

    @Test
    fun positioning_requires_the_pose_to_be_held() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0))
        // One millisecond short of the hold, so nothing but time is missing.
        machine.onFrame(frame(atMs = testConfig.positioningHoldMs - 1))

        val state = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.OK, state.hint)
        assertTrue(state.holdProgress > 0.9f, "expected near-complete hold, got $state")

        machine.onFrame(frame(atMs = testConfig.positioningHoldMs))
        assertIs<LivenessState.ChallengeActive>(machine.state.value)
    }

    @Test
    fun a_hold_broken_midway_restarts_the_countdown() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0))
        // Looking away resets the run, so the original deadline no longer counts.
        machine.onFrame(frame(atMs = 400, yaw = 40f))
        machine.onFrame(frame(atMs = 500))
        machine.onFrame(frame(atMs = 500 + testConfig.positioningHoldMs - 50))

        assertIs<LivenessState.Positioning>(machine.state.value)

        machine.onFrame(frame(atMs = 500 + testConfig.positioningHoldMs))
        assertIs<LivenessState.ChallengeActive>(machine.state.value)
    }

    @Test
    fun outside_guide_frames_restart_the_three_second_positioning_hold() {
        val machine = ChallengeMachine(
            challenges = listOf(Challenge.TurnLeft),
            config = testConfig.copy(positioningHoldMs = 3_000),
        )

        machine.onFrame(frame(atMs = 0))
        machine.onFrame(frame(atMs = 2_900, insideGuide = false))

        val outside = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.OUTSIDE_GUIDE, outside.hint)
        assertEquals(0f, outside.holdProgress)

        machine.onFrame(frame(atMs = 3_000))
        machine.onFrame(frame(atMs = 5_999))
        assertIs<LivenessState.Positioning>(machine.state.value)

        machine.onFrame(frame(atMs = 6_000))
        assertIs<LivenessState.ChallengeActive>(machine.state.value)
    }

    @Test
    fun outside_guide_turn_action_does_not_enter_its_return_phase() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(atMs = startedAt + 100, yaw = -40f, insideGuide = false))

        val blocked = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, blocked.phase)
        assertEquals(PositioningHint.OUTSIDE_GUIDE, blocked.hint)

        machine.onFrame(frame(atMs = startedAt + 200, yaw = 0f))
        val resumed = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, resumed.phase)
        assertNull(resumed.hint)
    }

    @Test
    fun outside_guide_right_turn_extreme_does_not_enter_its_return_phase() {
        val machine = machine(Challenge.TurnRight)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(atMs = startedAt + 100, yaw = 40f, insideGuide = false))

        val blocked = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, blocked.phase)
        assertEquals(PositioningHint.OUTSIDE_GUIDE, blocked.hint)

        machine.onFrame(frame(atMs = startedAt + 200, yaw = 0f))
        val resumed = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, resumed.phase)
        assertNull(resumed.hint)
    }

    @Test
    fun outside_guide_frame_restarts_a_center_challenge_hold() {
        val machine = ChallengeMachine(
            challenges = listOf(Challenge.Center),
            config = testConfig.copy(centerHoldMs = 3_000),
        )
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(atMs = startedAt + 100))
        machine.onFrame(frame(atMs = startedAt + 1_500))
        machine.onFrame(frame(atMs = startedAt + 2_500, insideGuide = false))

        val blocked = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, blocked.phase)
        assertEquals(PositioningHint.OUTSIDE_GUIDE, blocked.hint)

        machine.onFrame(frame(atMs = startedAt + 2_600))
        machine.onFrame(frame(atMs = startedAt + 4_000))
        machine.onFrame(frame(atMs = startedAt + 5_400))
        assertIs<LivenessState.ChallengeActive>(machine.state.value)

        machine.onFrame(frame(atMs = startedAt + 5_600))
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    @Test
    fun a_center_challenge_needs_a_sustained_frontal_hold() {
        val machine = machine(Challenge.TurnLeft, Challenge.Center)
        val startedAt = machine.completePositioning()
        val afterLeft = machine.performTurn(startedAt, yawAtExtreme = -30f)

        val active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(Challenge.Center, active.challenge)

        machine.onFrame(frame(afterLeft + 100))
        machine.onFrame(frame(afterLeft + 100 + testConfig.centerHoldMs - 1))
        assertIs<LivenessState.ChallengeActive>(machine.state.value)

        machine.onFrame(frame(afterLeft + 100 + testConfig.centerHoldMs))
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    // --- The turn is a movement, not a pose -----------------------------------

    @Test
    fun reaching_the_angle_is_not_enough_without_the_return() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(startedAt + 100, yaw = -40f))
        var active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.RETURNING_TO_CENTER, active.phase)

        // Still turned: a photograph held at the right angle gets exactly this far.
        machine.onFrame(frame(startedAt + 200, yaw = -30f))
        active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.RETURNING_TO_CENTER, active.phase)

        machine.onFrame(frame(startedAt + 300, yaw = -2f))
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    @Test
    fun a_turn_just_short_of_the_threshold_does_not_count() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(startedAt + 100, yaw = -(testConfig.turnThresholdDeg - 1f)))

        val active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, active.phase)
    }

    @Test
    fun turning_the_wrong_way_neither_completes_nor_pre_completes() {
        val machine = machine(Challenge.TurnLeft, Challenge.TurnRight)
        val startedAt = machine.completePositioning()

        // A full right turn while the left one was asked for.
        machine.onFrame(frame(startedAt + 100, yaw = 45f))
        machine.onFrame(frame(startedAt + 200, yaw = 0f))

        var active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(Challenge.TurnLeft, active.challenge)
        assertEquals(0, active.index)
        assertEquals(ChallengePhase.AWAITING_ACTION, active.phase)

        // Now do the left turn properly. The right turn performed a moment ago
        // must not have been banked against the challenge that comes next.
        val afterLeft = machine.performTurn(startedAt + 200, yawAtExtreme = -35f)
        active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(Challenge.TurnRight, active.challenge)
        assertEquals(ChallengePhase.AWAITING_ACTION, active.phase)

        machine.performTurn(afterLeft, yawAtExtreme = 35f)
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    // --- Deadlines ------------------------------------------------------------

    @Test
    fun a_challenge_that_is_never_performed_times_out() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        // Frames keep arriving with a visible, centred face — the user simply is
        // not turning — so this can only be the challenge deadline, not face loss.
        var t = startedAt + 1_000
        while (t <= startedAt + testConfig.challengeTimeoutMs + 1_000) {
            machine.onFrame(frame(t))
            t += 1_000
        }

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun positioning_times_out_on_its_own_deadline() {
        val machine = machine(Challenge.TurnLeft)
        machine.onFrame(frame(atMs = 0, faceRatio = 0.10f))

        machine.onFrame(frame(testConfig.positioningTimeoutMs + 1, faceRatio = 0.10f))

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun onTick_expires_a_session_the_camera_stopped_feeding() {
        val machine = machine(Challenge.TurnLeft)
        machine.onFrame(frame(atMs = 0))

        // No frames at all after this: without onTick the deadline would never be
        // observed, because the only clock is the frames that stopped arriving.
        machine.onTick(testConfig.positioningTimeoutMs + 1)

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun onTick_is_a_no_op_before_the_session_starts() {
        val machine = machine(Challenge.TurnLeft)

        machine.onTick(nowMs = 10_000_000)

        assertEquals(LivenessState.Idle, machine.state.value)
    }

    // --- The face itself ------------------------------------------------------

    @Test
    fun a_face_that_disappears_mid_challenge_fails_the_session() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(startedAt + 200, faceCount = 0))
        machine.onFrame(frame(startedAt + testConfig.faceLostGraceMs + 1, faceCount = 0))

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.FACE_LOST, failed.reason)
    }

    @Test
    fun a_brief_detector_dropout_is_survivable() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        // Real detectors drop frames. A session that died on one would be unusable.
        machine.onFrame(frame(startedAt + 200, faceCount = 0))
        machine.onFrame(frame(startedAt + 400, faceCount = 0))
        val state = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(PositioningHint.NO_FACE, state.hint)

        machine.performTurn(startedAt + 400, yawAtExtreme = -35f)
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    @Test
    fun a_missing_face_while_positioning_only_prompts() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0, faceCount = 0))
        machine.onFrame(frame(atMs = 5_000, faceCount = 0))

        // Nothing has been asked of the user yet, so there is nothing to fail.
        val state = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.NO_FACE, state.hint)
    }

    @Test
    fun a_changed_tracking_id_fails_as_a_swap() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        // The person who positioned is not the person completing the challenge.
        machine.onFrame(frame(startedAt + 100, yaw = -35f, trackingId = 2))

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.FACE_SWAPPED, failed.reason)
    }

    @Test
    fun a_swap_during_positioning_fails_just_as_hard() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0, trackingId = 7))
        machine.onFrame(frame(atMs = 100, trackingId = 8))

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.FACE_SWAPPED, failed.reason)
    }

    @Test
    fun frames_without_a_tracking_id_are_accepted() {
        // Detectors in single-image mode report no identity. Refusing to work
        // there would be worse than losing the swap check on those platforms.
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0, trackingId = null))
        machine.onFrame(frame(atMs = 800, trackingId = null))
        machine.performTurn(800, yawAtExtreme = -35f, trackingId = null)

        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    @Test
    fun a_second_face_ends_the_session_immediately() {
        val machine = machine(Challenge.TurnLeft)
        machine.completePositioning()

        machine.onFrame(frame(atMs = 1_000, faceCount = 2))

        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.MULTIPLE_FACES, failed.reason)
    }

    // --- Framing --------------------------------------------------------------

    @Test
    fun a_face_too_far_away_cannot_finish_positioning() {
        val machine = machine(Challenge.TurnLeft)
        val tooSmall = testConfig.minFaceRatio - 0.05f

        machine.onFrame(frame(atMs = 0, faceRatio = tooSmall))
        machine.onFrame(frame(atMs = 2_000, faceRatio = tooSmall))

        val state = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.MOVE_CLOSER, state.hint)
        assertEquals(0f, state.holdProgress)
    }

    @Test
    fun a_face_too_far_away_cannot_complete_a_challenge() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()
        val tooSmall = testConfig.minFaceRatio - 0.05f

        // A textbook turn, measured on a face too small to measure reliably.
        machine.onFrame(frame(startedAt + 100, yaw = -40f, faceRatio = tooSmall))
        machine.onFrame(frame(startedAt + 200, yaw = 0f, faceRatio = tooSmall))

        val active = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals(ChallengePhase.AWAITING_ACTION, active.phase)
        assertEquals(PositioningHint.MOVE_CLOSER, active.hint)
    }

    @Test
    fun a_face_filling_the_frame_is_told_to_back_off() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0, faceRatio = testConfig.maxFaceRatio + 0.05f))

        val state = assertIs<LivenessState.Positioning>(machine.state.value)
        assertEquals(PositioningHint.MOVE_AWAY, state.hint)
    }

    @Test
    fun positioning_hints_name_the_thing_actually_wrong() {
        val machine = machine(Challenge.TurnLeft)

        machine.onFrame(frame(atMs = 0, brightness = testConfig.minBrightness - 10f))
        assertEquals(
            PositioningHint.TOO_DARK,
            assertIs<LivenessState.Positioning>(machine.state.value).hint,
        )

        machine.onFrame(frame(atMs = 100, sharpness = testConfig.minSharpness - 5f))
        assertEquals(
            PositioningHint.LOW_QUALITY,
            assertIs<LivenessState.Positioning>(machine.state.value).hint,
        )

        machine.onFrame(frame(atMs = 200, roll = testConfig.maxRollDeg + 5f))
        assertEquals(
            PositioningHint.STRAIGHTEN_HEAD,
            assertIs<LivenessState.Positioning>(machine.state.value).hint,
        )

        machine.onFrame(frame(atMs = 300, yaw = testConfig.centerToleranceDeg + 5f))
        assertEquals(
            PositioningHint.LOOK_STRAIGHT,
            assertIs<LivenessState.Positioning>(machine.state.value).hint,
        )
    }

    // --- Frame selection ------------------------------------------------------

    @Test
    fun the_primary_frame_is_the_most_frontal_and_sharpest() {
        val machine = machine(Challenge.TurnLeft)

        val blurryButFrontal = frame(atMs = 0, yaw = 0f, sharpness = 30f)
        val sharpButAngled = frame(atMs = 200, yaw = 8f, sharpness = 200f)
        val best = frame(atMs = 400, yaw = 0f, sharpness = 200f)
        val mediocre = frame(atMs = 800, yaw = 6f, sharpness = 100f)

        listOf(blurryButFrontal, sharpButAngled, best, mediocre).forEach(machine::onFrame)
        machine.performTurn(800, yawAtExtreme = -35f)
        machine.completeCapture()

        val done = assertIs<LivenessState.Done>(machine.state.value)
        assertSame(best, done.evidence.primary)
    }

    @Test
    fun each_challenge_keeps_the_frame_at_its_furthest_point() {
        val machine = machine(Challenge.TurnLeft)
        val startedAt = machine.completePositioning()

        machine.onFrame(frame(startedAt + 100, yaw = -26f))
        val furthest = frame(startedAt + 200, yaw = -48f)
        machine.onFrame(furthest)
        machine.onFrame(frame(startedAt + 300, yaw = -30f))
        machine.onFrame(frame(startedAt + 400, yaw = 0f))
        machine.completeCapture()

        val done = assertIs<LivenessState.Done>(machine.state.value)
        assertEquals(1, done.evidence.challenges.size)
        assertSame(furthest, done.evidence.challenges.single().frame)
    }

    @Test
    fun evidence_lists_the_primary_frame_first() {
        val machine = machine(Challenge.TurnLeft, Challenge.TurnRight)
        val startedAt = machine.completePositioning()
        val afterLeft = machine.performTurn(startedAt, yawAtExtreme = -35f)
        machine.performTurn(afterLeft, yawAtExtreme = 35f)
        machine.completeCapture()

        val evidence = assertIs<LivenessState.Done>(machine.state.value).evidence
        assertEquals(3, evidence.frames.size)
        assertSame(evidence.primary, evidence.frames.first())
        assertTrue(evidence.durationMs > 0)
    }

    // --- Lifecycle ------------------------------------------------------------

    @Test
    fun a_terminal_session_ignores_everything_that_follows() {
        val machine = machine(Challenge.TurnLeft)
        machine.completePositioning()
        machine.onFrame(frame(atMs = 1_000, faceCount = 2))
        val failed = assertIs<LivenessState.Failed>(machine.state.value)

        // A camera keeps delivering frames after the session ends; none of them
        // may resurrect it.
        machine.onFrame(frame(atMs = 1_100))
        machine.performTurn(1_100, yawAtExtreme = -35f)
        machine.onTick(2_000)

        assertEquals(failed, machine.state.value)
    }

    @Test
    fun cancel_ends_the_session_and_is_idempotent() {
        val machine = machine(Challenge.TurnLeft)
        machine.completePositioning()

        machine.cancel()
        val failed = assertIs<LivenessState.Failed>(machine.state.value)
        assertEquals(FailureReason.CANCELLED, failed.reason)

        machine.cancel()
        assertEquals(failed, machine.state.value)
    }

    @Test
    fun reset_makes_a_used_machine_reusable() {
        val machine = machine(Challenge.TurnLeft)
        machine.completePositioning()
        machine.onFrame(frame(atMs = 1_000, trackingId = 99))
        assertIs<LivenessState.Failed>(machine.state.value)

        machine.reset()
        assertEquals(LivenessState.Idle, machine.state.value)

        // The old tracking id and the old deadlines must both be gone.
        val startedAt = machine.completePositioning(startMs = 50_000)
        machine.performTurn(startedAt, yawAtExtreme = -35f)
        assertEquals(LivenessState.Capturing, machine.state.value)
    }

    @Test
    fun completeCapture_is_rejected_outside_Capturing() {
        val machine = machine(Challenge.TurnLeft)

        assertFailsWith<IllegalStateException> { machine.completeCapture() }

        machine.completePositioning()
        assertFailsWith<IllegalStateException> { machine.completeCapture() }
    }

    @Test
    fun a_session_needs_at_least_one_challenge() {
        assertFailsWith<IllegalArgumentException> { ChallengeMachine(emptyList()) }
    }

    @Test
    fun the_state_carries_progress_and_a_spanish_instruction() {
        val machine = machine(Challenge.TurnLeft, Challenge.TurnRight)
        val startedAt = machine.completePositioning()

        val first = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals("Gira la cabeza a la izquierda", first.instructionEs)
        assertEquals(0f, first.progress)

        machine.onFrame(frame(startedAt + 100, yaw = -35f))
        assertEquals("Regresa a ver de frente", machine.state.value.instructionEs)

        machine.onFrame(frame(startedAt + 200, yaw = 0f))
        val second = assertIs<LivenessState.ChallengeActive>(machine.state.value)
        assertEquals("Gira la cabeza a la derecha", second.instructionEs)
        assertEquals(0.5f, second.progress)
        assertNull(second.hint)
    }
}
