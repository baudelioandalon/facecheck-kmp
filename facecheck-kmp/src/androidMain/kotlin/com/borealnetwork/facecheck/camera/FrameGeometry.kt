package com.borealnetwork.facecheck.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.FrameQuality
import com.google.mlkit.vision.face.Face

/**
 * Turning what ML Kit found into the frames
 * [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine] scores.
 *
 * Two coordinate spaces are in play and confusing them is the source of every
 * bug in this file:
 *
 *  - **buffer space** — the raw `ImageProxy`, `width` × `height`, as the sensor
 *    delivered it. The Y plane lives here.
 *  - **upright space** — the same image rotated by
 *    [ImageProxy.getImageInfo]`.rotationDegrees` so it is the right way up. ML
 *    Kit rotates internally and reports every box and landmark here.
 *
 * A face box therefore arrives in upright space while the pixels needed to
 * measure its sharpness are in buffer space, and one of them has to be mapped
 * onto the other. See [uprightRectToBuffer].
 */
internal object FrameGeometry {

    /** Size of the image after [rotationDegrees] is applied. */
    fun uprightSize(proxy: ImageProxy, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            proxy.height to proxy.width
        } else {
            proxy.width to proxy.height
        }

    /**
     * Map a rectangle from upright space back onto the un-rotated buffer.
     *
     * `rotationDegrees` is how far the buffer must be turned **clockwise** to
     * be upright, so this inverts that rotation. Rotation preserves chirality,
     * which is why nothing here touches the mirroring question — that is
     * [AnalysisMirroring]'s job and it applies to angles, not pixels.
     */
    fun uprightRectToBuffer(
        rect: Rect,
        rotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
    ): Rect {
        val maxX = bufferWidth - 1
        val maxY = bufferHeight - 1
        return when (rotationDegrees) {
            90 -> Rect(rect.top, maxY - rect.right, rect.bottom, maxY - rect.left)
            180 -> Rect(maxX - rect.right, maxY - rect.bottom, maxX - rect.left, maxY - rect.top)
            270 -> Rect(maxX - rect.bottom, rect.left, maxX - rect.top, rect.right)
            else -> Rect(rect)
        }.also { it.sort() }
    }

    /**
     * The frame emitted when the detector found nothing.
     *
     * Every pose field is zero and meaningless; `faceCount = 0` is the only part
     * the machine reads, and it is what starts the face-lost grace period.
     * Emitting an empty frame rather than staying silent is deliberate: silence
     * is indistinguishable from a stalled camera, and the machine's deadlines
     * are driven by the frames it receives.
     */
    fun noFace(timestampMs: Long): FaceFrame = FaceFrame(
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
        leftEyeOpen = null,
        rightEyeOpen = null,
        faceRatio = 0f,
        trackingId = null,
        timestampMs = timestampMs,
        quality = FrameQuality(sharpness = 0f, brightness = 0f, detectorScore = 0f),
        faceCount = 0,
    )

    /**
     * Build a frame from the face the session should be scoring.
     *
     * @param face the largest face in the frame. With more than one present the
     *   machine fails the session outright, but it still needs a well-formed
     *   frame to do that with.
     * @param faceCount how many the detector actually returned.
     * @param uprightWidth width of the rotated image, the denominator for
     *   [FaceFrame.faceRatio].
     */
    fun frameOf(
        face: Face,
        faceCount: Int,
        uprightWidth: Int,
        quality: FrameQuality,
        timestampMs: Long,
        mirroring: AnalysisMirroring,
    ): FaceFrame {
        // Both angles that describe rotation about an axis lying in the mirror
        // plane change sign when the image is reflected; pitch does not. See
        // AnalysisMirroring for the full derivation — this line is the entire
        // "which way is left" question, and it is worth being sure about.
        val horizontal = mirroring.signOfHorizontalAngles
        return FaceFrame(
            yaw = face.headEulerAngleY * horizontal,
            pitch = face.headEulerAngleX,
            roll = face.headEulerAngleZ * horizontal,
            // ML Kit's "left eye" is the subject's own left, matching FaceFrame.
            // Nothing scores the eyes today; they are carried so a host app can
            // avoid capturing a still on a blink.
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            faceRatio = if (uprightWidth <= 0) {
                0f
            } else {
                (face.boundingBox.width().toFloat() / uprightWidth).coerceIn(0f, 1f)
            },
            trackingId = face.trackingId,
            timestampMs = timestampMs,
            quality = quality,
            faceCount = faceCount,
        )
    }
}