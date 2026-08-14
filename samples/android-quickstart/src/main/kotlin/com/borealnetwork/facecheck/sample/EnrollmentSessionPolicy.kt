package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.Challenge
import com.borealnetwork.facecheck.liveness.LivenessConfig

/** Product-specific enrolment ergonomics for this runnable sample. */
internal object EnrollmentSessionPolicy {
    const val maxAttempts: Int = 3

    val challenges: List<Challenge> = listOf(
        Challenge.TurnLeft,
        Challenge.TurnRight,
        Challenge.Center,
    )

    val livenessConfig: LivenessConfig = LivenessConfig(
        positioningHoldMs = 3_000L,
        positioningTimeoutMs = 30_000,
        challengeTimeoutMs = 20_000,
        captureTimeoutMs = 15_000,
    )
}

internal class EnrollmentAttempt private constructor(val number: Int) {
    val label: String get() = "Intento $number de ${EnrollmentSessionPolicy.maxAttempts}"

    fun retry(): EnrollmentAttempt? =
        (number + 1).takeIf { it <= EnrollmentSessionPolicy.maxAttempts }?.let(::EnrollmentAttempt)

    companion object {
        val first: EnrollmentAttempt = EnrollmentAttempt(1)
    }
}
