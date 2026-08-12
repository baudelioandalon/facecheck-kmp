package com.borealnetwork.facecheck.camera

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlinx.coroutines.flow.Flow

/**
 * The platform's side of a liveness session: a stream of analysed frames plus
 * the ability to take one full-resolution still.
 *
 * The SDK orchestrates and the platform provides pixels. Nothing in
 * `commonMain` knows what a camera is; it only knows that frames arrive and
 * that a photo can be requested. That split is what lets the whole decision path
 * ([ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine]) be tested
 * without a device, and it is also the seam a host app can take over: an app
 * that already runs its own camera stack implements this interface directly
 * rather than using [createCameraController].
 */
interface CameraController {

    /**
     * Analysed frames, hot while the camera is running.
     *
     * Expected to be a hot stream that drops frames under back-pressure rather
     * than buffering them: a liveness session cares about what the face is doing
     * *now*, and a queue of stale frames would have the machine scoring a
     * movement the user finished seconds ago. Implementations should emit on a
     * background dispatcher.
     */
    val frames: Flow<FaceFrame>

    /**
     * Take one still photo, encoded as JPEG.
     *
     * Called once, at the end of a successful session. The bytes go straight to
     * the backend, so they must be a real encoded image (the backend re-decodes
     * and re-encodes them) and must be the front camera's un-mirrored output —
     * a mirrored selfie still matches, but it makes stored reference photos look
     * wrong to a human reviewing them later.
     *
     * @throws com.borealnetwork.facecheck.model.FaceCheckException with
     *   [CAMERA_UNAVAILABLE][com.borealnetwork.facecheck.model.FaceCheckErrorCode.CAMERA_UNAVAILABLE]
     *   when the capture fails.
     */
    suspend fun captureStill(): ByteArray

    /** Open the camera and start delivering [frames]. Idempotent. */
    fun start()

    /**
     * Release the camera. Idempotent.
     *
     * The SDK calls this in a `finally`, including when a session fails, so a
     * crashed verification never leaves the camera light on.
     */
    fun stop()
}

/** Capture parameters for the default platform controllers. */
data class CameraOptions(
    /** Longest edge of the still photo, in pixels. */
    val targetStillSize: Int = 1080,
    /** JPEG quality for the still, 1..100. */
    val jpegQuality: Int = 92,
    /** Front camera by default; a liveness selfie has no other sensible source. */
    val useFrontCamera: Boolean = true,
    /**
     * Mirror the *preview* only.
     *
     * Users expect a mirrored preview — an unmirrored one makes them turn the
     * wrong way. The captured still is never mirrored; see
     * [CameraController.captureStill].
     */
    val mirrorPreview: Boolean = true,
) {
    init {
        require(targetStillSize >= MIN_STILL_SIZE) {
            "targetStillSize must be at least $MIN_STILL_SIZE px, was $targetStillSize"
        }
        require(jpegQuality in 1..100) { "jpegQuality must be in 1..100, was $jpegQuality" }
    }

    private companion object {
        /** Below this the backend's detector has nothing left to work with. */
        const val MIN_STILL_SIZE = 480
    }
}