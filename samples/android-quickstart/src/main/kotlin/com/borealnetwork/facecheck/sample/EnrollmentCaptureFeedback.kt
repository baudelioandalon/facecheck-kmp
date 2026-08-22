package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.LivenessState

/** Emits one local acknowledgement each time a liveness step has been completed. */
internal class ChallengeCompletionFeedback {
    private var highestCompletedStep = 0
    private var highestEvidenceOrdinal = -1
    private var knownStepCount = 0

    fun consume(state: LivenessState): Boolean {
        if (state is LivenessState.CapturingEvidence) {
            val ordinal = state.role.ordinal
            if (ordinal <= highestEvidenceOrdinal) return false
            highestEvidenceOrdinal = ordinal
            return true
        }
        val completedStepCount = when (state) {
            is LivenessState.ChallengeActive -> {
                knownStepCount = state.total
                state.index
            }
            LivenessState.Capturing,
            is LivenessState.Done -> knownStepCount
            else -> return false
        }
        if (completedStepCount <= highestCompletedStep) return false
        highestCompletedStep = completedStepCount
        return true
    }
}
