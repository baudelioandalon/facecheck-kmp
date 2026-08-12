package com.borealnetwork.facecheck.liveness

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChallengePlanTest {

    @Test
    fun never_repeats_a_challenge_back_to_back() {
        // A repeat would read as "do the same thing twice" and users stop early.
        repeat(200) { seed ->
            val plan = ChallengePlan.random(ChallengePlan.MAX_CHALLENGES, Random(seed))
            plan.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun always_opens_with_a_turn() {
        // Center immediately after positioning is satisfied by the pose the user
        // already had to hold, so leading with it would ask for nothing.
        repeat(100) { seed ->
            val first = ChallengePlan.random(3, Random(seed)).first()
            assertTrue(
                first == Challenge.TurnLeft || first == Challenge.TurnRight,
                "expected a turn first, got $first",
            )
        }
    }

    @Test
    fun produces_exactly_the_requested_number() {
        (ChallengePlan.MIN_CHALLENGES..ChallengePlan.MAX_CHALLENGES).forEach { count ->
            assertEquals(count, ChallengePlan.random(count, Random(count)).size)
        }
    }

    @Test
    fun rejects_counts_outside_the_supported_range() {
        assertFailsWith<IllegalArgumentException> { ChallengePlan.random(0) }
        assertFailsWith<IllegalArgumentException> {
            ChallengePlan.random(ChallengePlan.MAX_CHALLENGES + 1)
        }
    }

    @Test
    fun a_seeded_plan_is_reproducible() {
        assertEquals(
            ChallengePlan.random(4, Random(42)),
            ChallengePlan.random(4, Random(42)),
        )
    }

    @Test
    fun every_challenge_carries_a_spanish_instruction_and_a_stable_id() {
        Challenge.all.forEach { challenge ->
            assertTrue(challenge.instructionEs.isNotBlank())
            assertTrue(challenge.id.isNotBlank())
            assertEquals(challenge, Challenge.fromId(challenge.id))
        }
    }
}

class LivenessConfigTest {

    @Test
    fun rejects_a_turn_threshold_inside_the_center_tolerance() {
        // Otherwise "turned" and "facing forward" are the same pose and every
        // challenge completes itself on the first frame.
        assertFailsWith<IllegalArgumentException> {
            LivenessConfig(turnThresholdDeg = 8f, centerToleranceDeg = 10f)
        }
    }

    @Test
    fun rejects_a_face_ratio_window_that_cannot_be_satisfied() {
        assertFailsWith<IllegalArgumentException> {
            LivenessConfig(minFaceRatio = 0.6f, maxFaceRatio = 0.5f)
        }
    }

    @Test
    fun rejects_a_positioning_timeout_shorter_than_its_own_hold() {
        assertFailsWith<IllegalArgumentException> {
            LivenessConfig(positioningHoldMs = 5_000, positioningTimeoutMs = 1_000)
        }
    }
}