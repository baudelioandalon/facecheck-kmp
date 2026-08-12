package com.borealnetwork.facecheck.liveness

/**
 * Thresholds and deadlines for [ChallengeMachine].
 *
 * These are ergonomics knobs, not security parameters. Loosening them makes the
 * session easier to pass on a bad phone in bad light; tightening them does not
 * make the SDK harder to defeat, because an attacker who controls the device
 * fabricates whatever [FaceFrame] values he likes regardless of what is set
 * here. Tune them against real users, not against a threat model.
 *
 * Defaults were picked so a person holding a phone at arm's length, indoors,
 * passes on the first try.
 */
data class LivenessConfig(
    /**
     * Minimum face width as a fraction of frame width.
     *
     * 0.25 keeps the face big enough that the still photo still has a usable
     * crop after the backend's own detector runs on it — the backend's default
     * `minFaceDivisor` of 5 means it will not even look for a face smaller than
     * a fifth of the frame.
     */
    val minFaceRatio: Float = 0.25f,

    /** Above this the user is too close and the face gets cropped by the frame. */
    val maxFaceRatio: Float = 0.90f,

    /** Degrees of yaw that count as "turned". */
    val turnThresholdDeg: Float = 25f,

    /** Degrees of yaw/pitch inside which the head counts as frontal. */
    val centerToleranceDeg: Float = 10f,

    /** Degrees of roll tolerated while positioning. */
    val maxRollDeg: Float = 20f,

    /** How long a good frontal pose must hold before the first challenge starts. */
    val positioningHoldMs: Long = 700,

    /** How long a frontal pose must hold to satisfy [Challenge.Center]. */
    val centerHoldMs: Long = 600,

    /** Deadline for reaching a usable starting pose. */
    val positioningTimeoutMs: Long = 20_000,

    /** Deadline for each individual challenge. */
    val challengeTimeoutMs: Long = 10_000,

    /** Deadline for the still capture once all challenges pass. */
    val captureTimeoutMs: Long = 8_000,

    /**
     * How long the face may be missing mid-session before the session fails.
     *
     * Non-zero because detectors drop a frame or two on any real device, and a
     * session that dies on a single dropped frame is unusable. Small because a
     * long gap is exactly when a swap would happen.
     */
    val faceLostGraceMs: Long = 1_500,

    /** Frames below this detector confidence are ignored while positioning. */
    val minDetectorScore: Float = 0.85f,

    /** Laplacian variance below this reads as motion blur. */
    val minSharpness: Float = 25f,

    /** Mean luminance bounds, 0..255, outside which the user is coached. */
    val minBrightness: Float = 50f,
    val maxBrightness: Float = 220f,
) {
    init {
        require(minFaceRatio > 0f && minFaceRatio < 1f) {
            "minFaceRatio must be in (0,1), was $minFaceRatio"
        }
        require(maxFaceRatio > minFaceRatio && maxFaceRatio <= 1f) {
            "maxFaceRatio must be in ($minFaceRatio,1], was $maxFaceRatio"
        }
        require(turnThresholdDeg > centerToleranceDeg) {
            "turnThresholdDeg ($turnThresholdDeg) must exceed centerToleranceDeg " +
                "($centerToleranceDeg), otherwise a turn and a centred head are the " +
                "same pose and every challenge self-completes"
        }
        require(centerToleranceDeg > 0f) { "centerToleranceDeg must be positive" }
        require(maxRollDeg > 0f) { "maxRollDeg must be positive" }
        require(positioningHoldMs >= 0) { "positioningHoldMs must not be negative" }
        require(centerHoldMs >= 0) { "centerHoldMs must not be negative" }
        require(positioningTimeoutMs > positioningHoldMs) {
            "positioningTimeoutMs must exceed positioningHoldMs"
        }
        require(challengeTimeoutMs > 0) { "challengeTimeoutMs must be positive" }
        require(captureTimeoutMs > 0) { "captureTimeoutMs must be positive" }
        require(faceLostGraceMs >= 0) { "faceLostGraceMs must not be negative" }
        require(minBrightness < maxBrightness) { "minBrightness must be below maxBrightness" }
    }
}