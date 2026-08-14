package com.borealnetwork.facecheck.liveness

/**
 * A face rectangle in an upright image coordinate space normalized to 0..1.
 *
 * The origin is the top-left corner: [left] and [right] grow to the right,
 * while [top] and [bottom] grow downwards.
 */
data class NormalizedFaceBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

/**
 * One analysed camera frame, as the platform's face detector saw it.
 *
 * This is the entire contract between the platform layer (CameraX + ML Kit on
 * Android, AVFoundation + Vision on iOS) and [ChallengeMachine]. Everything the
 * machine decides is a function of these values and nothing else, which is what
 * makes the machine testable without a camera.
 *
 * ### Sign conventions
 *
 * All angles are degrees, from the subject's own point of view, as if you were
 * standing in their shoes rather than looking at them:
 *
 *  - [yaw] negative = head turned to the subject's **left**, positive = right.
 *  - [pitch] negative = chin down, positive = chin up.
 *  - [roll] negative = head tilted towards the subject's left shoulder.
 *
 * ML Kit's `headEulerAngleY` and Vision's `yaw` both already use this
 * convention for a front camera; a platform that disagrees must flip the sign
 * before constructing a frame, not inside the machine.
 */
data class FaceFrame(
    /** Head rotation left/right, degrees. Negative is the subject's left. */
    val yaw: Float,
    /** Head rotation up/down, degrees. Negative is chin down. */
    val pitch: Float,
    /** Head tilt, degrees. Negative leans towards the subject's left shoulder. */
    val roll: Float,
    /** Eye-open probability 0..1, or null when the detector does not report it. */
    val leftEyeOpen: Float?,
    /** Eye-open probability 0..1, or null when the detector does not report it. */
    val rightEyeOpen: Float?,
    /** Face bounding-box width as a fraction of frame width, 0..1. */
    val faceRatio: Float,
    /**
     * The detector's tracking identity for this face.
     *
     * Stable for as long as the detector believes it is following the same face,
     * and this is the only continuity signal the machine has: a change mid-session
     * means the thing being tracked was swapped out. Null when the platform runs
     * in single-image mode, in which case the swap check cannot run at all.
     */
    val trackingId: Int?,
    /**
     * Monotonic milliseconds from the frame's own timestamp.
     *
     * The machine derives every deadline from this rather than from a clock, so
     * a test can drive ten seconds of session in microseconds and a slow device
     * measures elapsed time in frames it actually saw.
     */
    val timestampMs: Long,
    /** Per-frame image quality; see [FrameQuality]. */
    val quality: FrameQuality = FrameQuality(),
    /**
     * How many faces the detector found in this frame.
     *
     * Zero means the face was lost, which is legal for a moment and fatal if it
     * persists; more than one is fatal immediately, because with two faces in
     * frame there is no longer a single subject whose challenges are being
     * scored. The other fields describe the largest face when this is >= 1 and
     * are meaningless when it is 0.
     */
    val faceCount: Int = 1,
    /** The detected face rectangle, when the platform can report it. */
    val bounds: NormalizedFaceBounds? = null,
    /** Whether the detected face is fully contained by the active visual guide. */
    val insideGuide: Boolean = true,
) {
    /** True when exactly one face is present, i.e. the frame is scorable at all. */
    val hasSingleFace: Boolean get() = faceCount == 1

    /**
     * Whether both eyes read as closed.
     *
     * Not used as a challenge — a blink is trivially replayed and asking a user
     * to blink on cue fails often enough to be a support cost — but exposed so a
     * host app can avoid capturing a still on a blink.
     */
    val eyesClosed: Boolean
        get() {
            val left = leftEyeOpen ?: return false
            val right = rightEyeOpen ?: return false
            return left < EYES_CLOSED_THRESHOLD && right < EYES_CLOSED_THRESHOLD
        }

    private companion object {
        const val EYES_CLOSED_THRESHOLD = 0.25f
    }
}

/**
 * Cheap image-quality signals for one frame.
 *
 * The units match what the backend reports in
 * [FaceQuality][com.borealnetwork.facecheck.model.FaceQuality] so a platform can compute
 * them the same way on both sides and the numbers stay comparable.
 */
data class FrameQuality(
    /** Variance of the Laplacian over the face crop. Higher is sharper. */
    val sharpness: Float = DEFAULT_SHARPNESS,
    /** Mean luminance of the face crop, 0..255. */
    val brightness: Float = DEFAULT_BRIGHTNESS,
    /** Detector confidence for the box, 0..1. */
    val detectorScore: Float = DEFAULT_DETECTOR_SCORE,
) {
    companion object {
        const val DEFAULT_SHARPNESS: Float = 120f
        const val DEFAULT_BRIGHTNESS: Float = 128f
        const val DEFAULT_DETECTOR_SCORE: Float = 0.99f

        /**
         * Sharpness treated as "as good as it needs to be" when scoring frames
         * against each other. Laplacian variance is unbounded, so without a
         * ceiling one specular highlight would outrank a well-framed face.
         */
        const val SHARPNESS_REFERENCE: Float = 200f
    }
}
