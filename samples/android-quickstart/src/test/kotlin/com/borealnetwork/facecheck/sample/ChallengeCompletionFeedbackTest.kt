package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import com.borealnetwork.facecheck.liveness.ChallengePhase
import com.borealnetwork.facecheck.liveness.EvidenceRole
import com.borealnetwork.facecheck.liveness.LivenessState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChallengeCompletionFeedbackTest {

    @Test
    fun `enrollment celebrates each completed step exactly once`() {
        val feedback = ChallengeCompletionFeedback()

        assertFalse(feedback.consume(active(index = 0)))
        assertTrue(feedback.consume(active(index = 1)))
        assertFalse(feedback.consume(active(index = 1)))
        assertTrue(feedback.consume(active(index = 2)))
        assertTrue(feedback.consume(LivenessState.Capturing))
        assertFalse(feedback.consume(LivenessState.Capturing))
    }

    @Test
    fun `server evidence capture acknowledges every canonical role once`() {
        val feedback = ChallengeCompletionFeedback()

        assertTrue(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.FRONT_INITIAL)))
        assertFalse(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.FRONT_INITIAL)))
        assertTrue(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.TURN_FIRST)))
        assertTrue(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.CENTER_BETWEEN)))
        assertTrue(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.TURN_SECOND)))
        assertTrue(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.FRONT_FINAL)))
        assertFalse(feedback.consume(LivenessState.CapturingEvidence(EvidenceRole.FRONT_FINAL)))
    }

    private fun active(index: Int): LivenessState.ChallengeActive = LivenessState.ChallengeActive(
        challenge = EnrollmentSessionPolicy.challenges[index],
        index = index,
        total = EnrollmentSessionPolicy.challenges.size,
        phase = ChallengePhase.AWAITING_ACTION,
    )
}
