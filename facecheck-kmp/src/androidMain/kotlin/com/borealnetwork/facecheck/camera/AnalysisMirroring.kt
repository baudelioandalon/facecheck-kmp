package com.borealnetwork.facecheck.camera

/**
 * Whether the frames handed to the face detector were mirrored horizontally
 * before it saw them.
 *
 * ## Why this exists at all
 *
 * The preview the user watches and the buffer the detector analyses are **not
 * the same image**, and the difference is a left-right flip.
 *
 * `PreviewView` mirrors the front camera, because a selfie preview that is not
 * mirrored feels broken — people move the wrong way trying to centre
 * themselves. `ImageAnalysis` does no such thing: it hands over the sensor
 * buffer as it came, which for *any* camera pointed at a face is the
 * **observer's** view — what a person standing where the phone is would see.
 * In that view the subject's own left is on the image's **right**.
 *
 * ML Kit reports `headEulerAngleY` as positive when the face turns toward the
 * right side of *the image it was given*. So a user turning to their own left
 * produces a **positive** ML Kit yaw, while
 * [FaceFrame][com.borealnetwork.facecheck.liveness.FaceFrame] defines negative yaw as the
 * subject's left. The two disagree, and [NONE] is the setting that reconciles
 * them.
 *
 * ## Why it is a whole enum instead of a hard-coded minus sign
 *
 * Because a host app that runs its own pipeline may well mirror frames before
 * detection — anyone who fed the detector the same bitmap they drew on screen
 * has — and then the flip must **not** happen. Getting this backwards produces
 * an SDK that tells every user to turn left, fails them for turning left, and
 * passes them for turning right. It is invisible to every unit test, because a
 * test feeds the machine numbers rather than a head.
 *
 * ## The transform
 *
 * Going from the observer's view to the subject's own point of view is a
 * reflection about the image's vertical axis. A reflection negates rotation
 * about the vertical axis (yaw) and about the viewing axis (roll), and leaves
 * rotation about the horizontal axis (pitch) alone. That is exactly what
 * [signOfHorizontalAngles] encodes.
 *
 * Note that only the **yaw** sign can actually break a session:
 * [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine] consumes pitch
 * and roll solely through `abs()`. Roll is flipped anyway so the numbers a host
 * app reads off a [FaceFrame][com.borealnetwork.facecheck.liveness.FaceFrame] mean what
 * the documentation says they mean.
 */
enum class AnalysisMirroring {

    /**
     * Frames reach the detector un-mirrored — the CameraX default, and what
     * [AndroidCameraController] produces.
     *
     * Yaw and roll are negated on the way into a
     * [FaceFrame][com.borealnetwork.facecheck.liveness.FaceFrame].
     */
    NONE,

    /**
     * Frames were already flipped horizontally before detection, so the
     * detector's "image right" is the subject's own right.
     *
     * Angles pass through untouched. Only relevant to a host app that mirrors
     * frames itself; nothing in this SDK produces such frames.
     */
    HORIZONTAL,
    ;

    /**
     * Multiplier applied to yaw and roll to convert the detector's angles into
     * the subject's frame of reference. Pitch is never multiplied.
     */
    internal val signOfHorizontalAngles: Float
        get() = when (this) {
            NONE -> -1f
            HORIZONTAL -> 1f
        }
}