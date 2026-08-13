package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.LivenessState

/** Emits one local acknowledgement each time a liveness step has been completed. */
internal class ChallengeCompletionFeedback {
    private var highestCompletedStep = 0
    private var knownStepCount = 0

    fun consume(state: LivenessState): Boolean {
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

/** Terminal presentation for a failed save, where progress must no longer cover the retry card. */
internal data class EnrollmentRetryPresentation(
    val attempt: EnrollmentAttempt,
    val showsLoading: Boolean = false,
) {
    val nextAttempt: EnrollmentAttempt? get() = attempt.retry()

    companion object {
        fun from(attempt: EnrollmentAttempt): EnrollmentRetryPresentation = EnrollmentRetryPresentation(attempt)
    }
}
