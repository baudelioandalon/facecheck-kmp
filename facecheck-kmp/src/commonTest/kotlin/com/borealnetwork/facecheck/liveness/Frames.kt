package com.borealnetwork.facecheck.liveness

/**
 * Test fixtures for [ChallengeMachine].
 *
 * Defaults describe an ideal frame: one face, dead centre, well lit, sharp,
 * filling 40% of the frame. A test overrides exactly the field it is about,
 * which keeps the interesting value visible at the call site.
 */
internal fun frame(
    atMs: Long,
    yaw: Float = 0f,
    pitch: Float = 0f,
    roll: Float = 0f,
    faceRatio: Float = 0.40f,
    trackingId: Int? = 1,
    faceCount: Int = 1,
    insideGuide: Boolean = true,
    sharpness: Float = FrameQuality.DEFAULT_SHARPNESS,
    brightness: Float = FrameQuality.DEFAULT_BRIGHTNESS,
    detectorScore: Float = FrameQuality.DEFAULT_DETECTOR_SCORE,
): FaceFrame = FaceFrame(
    yaw = yaw,
    pitch = pitch,
    roll = roll,
    leftEyeOpen = 0.9f,
    rightEyeOpen = 0.9f,
    faceRatio = faceRatio,
    trackingId = trackingId,
    timestampMs = atMs,
    quality = FrameQuality(
        sharpness = sharpness,
        brightness = brightness,
        detectorScore = detectorScore,
    ),
    faceCount = faceCount,
    insideGuide = insideGuide,
)

/** Config with the production defaults, restated so tests read against a fixture. */
internal val testConfig = LivenessConfig()

/**
 * Walk the machine through positioning so a test can start at the first
 * challenge. Returns the timestamp of the frame that triggered the transition.
 */
internal fun ChallengeMachine.completePositioning(startMs: Long = 0): Long {
    onFrame(frame(startMs))
    val advancedAt = startMs + testConfig.positioningHoldMs + 100
    onFrame(frame(advancedAt))
    return advancedAt
}

/**
 * Perform a full turn: out past the threshold, then back to frontal.
 *
 * @return the timestamp of the frame that completed the movement.
 */
internal fun ChallengeMachine.performTurn(
    fromMs: Long,
    yawAtExtreme: Float,
    trackingId: Int? = 1,
): Long {
    onFrame(frame(fromMs + 100, yaw = yawAtExtreme, trackingId = trackingId))
    onFrame(frame(fromMs + 200, yaw = 0f, trackingId = trackingId))
    return fromMs + 200
}
