package com.borealnetwork.facecheck.camera

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * How the camera buffer has to be rotated to be upright for the user.
 *
 * The camera sensor does not rotate with the phone: unless the capture
 * connection is told otherwise, buffers arrive in the sensor's native landscape
 * regardless of how the device is held. A detector that skips this ends up
 * looking for an upright face in a sideways image and simply finds nothing,
 * which on a device looks exactly like "the camera is broken".
 *
 * [IosCameraController] pins its connections to portrait, so it passes [UP] and
 * AVFoundation does the rotation. A host driving its own capture session
 * usually cannot, and passes the orientation matching its connection instead.
 *
 * The values are the EXIF/`CGImagePropertyOrientation` codes, because that is
 * what both Vision and ImageIO speak. [exifValue] is exported so a Swift bridge
 * can build a `CGImagePropertyOrientation(rawValue:)` without a mapping table of
 * its own.
 */
enum class FrameOrientation(val exifValue: UInt) {
    /** Buffer is already upright. */
    UP(1u),

    /** Rotate 90° clockwise to make it upright — the portrait case. */
    RIGHT(6u),

    /** Rotate 90° counter-clockwise — landscape with the home button right. */
    LEFT(8u),

    /** Rotate 180° — upside-down portrait. */
    DOWN(3u),
    ;

    /** Quarter-turns clockwise needed to make the buffer upright. */
    internal val quarterTurnsClockwise: Int
        get() = when (this) {
            UP -> 0
            RIGHT -> 1
            DOWN -> 2
            LEFT -> 3
        }

    /** True when the upright image swaps the buffer's width and height. */
    internal val swapsAxes: Boolean
        get() = this == RIGHT || this == LEFT
}

/**
 * The seam between the iOS capture pipeline and whatever detects faces in it.
 *
 * ### Why this exists
 *
 * The obvious way to get ML Kit's face detector into a Kotlin/Native SDK is
 * cinterop against its XCFrameworks. In practice that binds the SDK to one ML
 * Kit version, one Firebase transitive graph and one linker configuration, and
 * every consumer who disagrees with any of those cannot build at all. So the
 * SDK does not link a detector: it declares this interface, ships a default
 * implementation built on Apple's own Vision framework (which needs no
 * dependency at all), and lets a host app that wants ML Kit implement the same
 * interface in Swift and inject it.
 *
 * ### Detector parity is not free
 *
 * Vision and ML Kit do not report the same things:
 *
 *  - **Eye openness.** ML Kit returns a *calibrated probability* from a trained
 *    classifier. Vision returns landmark points and nothing else, so the default
 *    implementation has to derive openness geometrically from the eye aspect
 *    ratio (EAR) and squash it into 0..1 with hand-picked constants — see
 *    [VisionFaceDetector]. Those constants are a guess until they are measured
 *    on real users, which is exactly why any blink threshold has to be a
 *    per-platform number and never a shared one.
 *  - **Tracking identity.** ML Kit's stream mode assigns a stable tracking id.
 *    Vision's `VNDetectFaceLandmarksRequest` does not track at all — every frame
 *    gets a fresh UUID — so the default implementation reports a null
 *    [FaceFrame.trackingId] and the machine's face-swap check is inert on iOS.
 *    A Swift bridge backed by ML Kit restores it.
 *
 * ### Threading
 *
 * [analyze] is called on the capture session's own serial queue, one frame at a
 * time, never re-entrantly. It must not block: the video output discards late
 * frames, so a slow detector costs frame rate rather than latency, but a
 * detector that blocks for a second stalls the whole session.
 */
interface FaceDetectorBridge {

    /**
     * Analyse one camera frame.
     *
     * @param sampleBuffer the frame's `CMSampleBuffer`, valid **only** for the
     *   duration of this call. It is exported to Swift as an
     *   `UnsafeMutableRawPointer`; recover the real type with
     *   `Unmanaged<CMSampleBuffer>.fromOpaque(sampleBuffer).takeUnretainedValue()`.
     *   Retain it if you need it past the call — the capture system reuses the
     *   underlying memory the moment this returns.
     *
     *   A `CMSampleBuffer` rather than the bare `CVPixelBuffer` because that is
     *   what the detectors a bridge is written for actually want: ML Kit takes
     *   `VisionImage(buffer:)`, and Vision reaches the pixel buffer from it in
     *   one call. Handing over the pixel buffer instead would make the common
     *   case do a conversion the uncommon case does not need.
     * @param orientation how to rotate the buffer to upright. Pass it straight
     *   through to the detector rather than rotating pixels yourself.
     * @param timestampMs the frame's own presentation timestamp in milliseconds,
     *   monotonic. This becomes [FaceFrame.timestampMs], from which every
     *   deadline in the session is derived, so it must come from the buffer and
     *   not from a wall clock.
     *
     * @return the frame's analysis, or null to drop this frame entirely. Return
     *   a [FaceFrame] with `faceCount = 0` for "looked, found nobody" — that is
     *   a real observation the session reacts to. Null means "could not look",
     *   which the session ignores.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun analyze(
        sampleBuffer: COpaquePointer,
        orientation: FrameOrientation,
        timestampMs: Long,
    ): FaceFrame?

    /**
     * Release per-session resources. Idempotent.
     *
     * Called every time the camera stops, and a controller can be started again
     * afterwards — a user who fails a session and retries goes through exactly
     * that path. So this must leave the bridge *usable*: drop caches and
     * in-flight work, not the detector itself.
     *
     * Kotlin gives this a body, but the Objective-C export marks every interface
     * member `@required`, so a Swift implementation has to write `func close() {}`
     * even when it has nothing to release.
     */
    fun close() {}
}