package com.borealnetwork.facecheck.camera

import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.FrameQuality
import com.borealnetwork.facecheck.liveness.NormalizedFaceBounds
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGPoint
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferRef
import platform.ImageIO.CGImagePropertyOrientation
import platform.Vision.VNDetectFaceLandmarksRequest
import platform.Vision.VNFaceLandmarkRegion2D
import platform.Vision.VNFaceObservation
import platform.Vision.VNImageRequestHandler
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The default [FaceDetectorBridge]: Apple's Vision framework, no dependencies.
 *
 * Runs one `VNDetectFaceLandmarksRequest` per frame and turns the largest
 * observation into a [FaceFrame].
 *
 * ## Sign conventions, and why they are computed rather than assumed
 *
 * [FaceFrame] fixes one convention for both platforms: **negative yaw is the
 * subject turning to their own left**, negative pitch is chin down, negative
 * roll is a tilt toward the subject's left shoulder. If iOS disagreed with
 * Android by a sign, the shared [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine]
 * would keep passing on one platform and fail on the other while every threshold
 * looked correct in both — the SDK would tell iOS users to turn left and then
 * time them out for doing it. There is no test that catches this; only a device.
 *
 * Two things decide the sign of what Vision reports:
 *
 *  1. **Which way the image is mirrored.** A mirrored buffer flips yaw and roll
 *     and leaves pitch alone. This is not a constant to guess at, it is a
 *     property of the connection that produced the buffer, so it is a
 *     constructor parameter ([mirrored]) and the flip is derived from it.
 *     [IosCameraController] forces `isVideoMirrored = false` on its analysis
 *     connection precisely so this input is known rather than inferred.
 *  2. **Vision's own axis direction**, captured in [YAW_SIGN] / [PITCH_SIGN] /
 *     [ROLL_SIGN]. These are the *only* place a sign is hard-coded.
 *
 * To re-verify on a device in about a minute: set
 * `FaceCheck.initialize(config.copy(logLevel = FaceCheckLogLevel.DEBUG))`, run a
 * session, and turn your head to your **own left**. The logged `yaw` must be
 * negative. If it is positive, flip [YAW_SIGN] here — never compensate by
 * swapping [Challenge.TurnLeft][com.borealnetwork.facecheck.liveness.Challenge.TurnLeft]
 * and `TurnRight`, and never widen a threshold to hide it.
 *
 * ## Eye openness is an estimate here, not a probability
 *
 * ML Kit reports eye openness from a trained classifier, already calibrated to
 * 0..1. Vision reports no such thing: it reports landmark points. This class
 * derives openness from the **eye aspect ratio** — the eye opening divided by
 * the eye width — and maps it linearly onto 0..1 between [EAR_CLOSED] and
 * [EAR_OPEN]. Those two numbers are geometry, not a model, and they vary with
 * eyelid shape, glasses and camera angle far more than a classifier does.
 *
 * The practical consequence: **blink thresholds must be per-platform.** A
 * threshold measured against ML Kit's probabilities does not transfer to a
 * number this class produces, and treating them as interchangeable produces an
 * SDK that reads half its users as permanently asleep. The SDK does not use a
 * blink as a challenge for exactly this kind of reason, so nothing fails if this
 * estimate is mediocre; it exists so a host app can avoid capturing a still
 * mid-blink.
 *
 * @param mirrored whether the buffers handed to [analyze] are horizontally
 *   mirrored. False for the SDK's own capture path.
 */
@OptIn(ExperimentalForeignApi::class)
class VisionFaceDetector(
    private val mirrored: Boolean = false,
) : FaceDetectorBridge {

    private val request = VNDetectFaceLandmarksRequest()

    override fun analyze(
        sampleBuffer: COpaquePointer,
        orientation: FrameOrientation,
        timestampMs: Long,
    ): FaceFrame? {
        val sample: CMSampleBufferRef = sampleBuffer.reinterpret()
        val buffer: CVPixelBufferRef = CMSampleBufferGetImageBuffer(sample) ?: return null
        val detected = detect(buffer, orientation) ?: return null

        // Vision has no minimum-size option, so the filter ML Kit gets for free
        // from `FaceDetectorOptions.setMinFaceSize` is applied by hand — and it
        // has to happen *before* faceCount is taken. ChallengeMachine fails the
        // session immediately on faceCount > 1, so counting the unfiltered list
        // would end a session the moment a photo on the wall or somebody three
        // metres back entered frame, on iOS only.
        val observations = detected.filter {
            it.boundingBox.useContents { size.width } >= MIN_FACE_WIDTH_RATIO.toDouble()
        }

        if (observations.isEmpty()) {
            return FaceFrame(
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                leftEyeOpen = null,
                rightEyeOpen = null,
                faceRatio = 0f,
                trackingId = null,
                timestampMs = timestampMs,
                faceCount = 0,
            )
        }

        // The machine scores one subject; when several faces are present it only
        // needs to know that, and the largest is the one the user is most likely
        // to be. Sorting by area rather than by Vision's own ordering keeps the
        // choice stable frame to frame.
        val face = observations.maxBy { it.boundingBox.useContents { size.width * size.height } }
        val angles = eulerDegrees(face)
        val eyes = eyeOpenness(face, buffer, orientation)
        val box = face.boundingBox.useContents { size.width.toFloat() }
        val bounds = face.boundingBox.useContents {
            NormalizedFaceBounds(
                left = origin.x.toFloat(),
                top = 1f - (origin.y + size.height).toFloat(),
                right = (origin.x + size.width).toFloat(),
                bottom = 1f - origin.y.toFloat(),
            )
        }
        val stats = pixelStats(buffer, face, orientation)

        return FaceFrame(
            yaw = angles.yaw,
            pitch = angles.pitch,
            roll = angles.roll,
            leftEyeOpen = eyes?.left,
            rightEyeOpen = eyes?.right,
            faceRatio = box.coerceIn(0f, 1f),
            // Vision's detect requests do not track: every frame produces a new
            // UUID for the same face. Reporting a synthesised id would make the
            // machine's swap check look alive while proving nothing, and
            // reporting the UUID itself would fail the session on every frame.
            // Null is the honest answer; see FaceDetectorBridge.
            trackingId = null,
            timestampMs = timestampMs,
            quality = FrameQuality(
                sharpness = stats?.sharpness ?: FrameQuality.DEFAULT_SHARPNESS,
                brightness = stats?.brightness ?: FrameQuality.DEFAULT_BRIGHTNESS,
                detectorScore = face.confidence,
            ),
            faceCount = observations.size,
            bounds = bounds,
        )
    }

    override fun close() = Unit

    // --- Vision plumbing ------------------------------------------------------

    // `options` is an empty Kotlin Map bridged to an NSDictionary, which cinterop
    // still marks beta. Vision requires the argument even when there is nothing
    // to put in it.
    @OptIn(BetaInteropApi::class)
    private fun detect(
        buffer: CVPixelBufferRef,
        orientation: FrameOrientation,
    ): List<VNFaceObservation>? = memScoped {
        val handler = VNImageRequestHandler(
            cVPixelBuffer = buffer,
            orientation = orientation.toCoreGraphics(),
            options = emptyMap<Any?, Any?>(),
        )
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ok = handler.performRequests(listOf(request), error.ptr)
        if (!ok) {
            FaceCheckLogger.warn { "Vision request failed" }
            return@memScoped null
        }
        request.results.orEmpty().filterIsInstance<VNFaceObservation>()
    }

    /**
     * Vision's head pose in [FaceFrame]'s convention, degrees.
     *
     * Vision reports radians and omits anything it is not confident about;
     * `pitch` in particular is absent before iOS 15, in which case zero is
     * reported. Zero is the *safe* absence value here rather than a lie the
     * session acts on: the machine only ever compares `abs(pitch)` against a
     * tolerance, so an absent pitch degrades to "pitch is not checked" instead
     * of blocking a user who is holding their head perfectly well.
     */
    private fun eulerDegrees(face: VNFaceObservation): Euler {
        val mirrorFlip = if (mirrored) -1f else 1f
        return Euler(
            yaw = (face.yaw.toDegrees() * YAW_SIGN * mirrorFlip),
            pitch = (face.pitch.toDegrees() * PITCH_SIGN),
            // Roll is a rotation in the image plane, so mirroring reverses it
            // just as it reverses yaw.
            roll = (face.roll.toDegrees() * ROLL_SIGN * mirrorFlip),
        )
    }

    private data class Euler(val yaw: Float, val pitch: Float, val roll: Float)

    private fun NSNumber?.toDegrees(): Float =
        this?.let { (it.doubleValue * 180.0 / PI).toFloat() } ?: 0f

    // --- Eye aspect ratio -----------------------------------------------------

    /**
     * Openness for both eyes, or null when Vision reported no landmarks.
     *
     * Vision's `leftEye` is the eye on the left of the *image*, which for a
     * non-mirrored frame is the subject's right. The pair is reported in
     * Vision's order without trying to reconcile that with ML Kit's, because
     * nothing in the SDK acts on a single eye — [FaceFrame.eyesClosed] requires
     * both — and a wink-style challenge built on either detector's handedness
     * would be a coin flip. Do not add one.
     */
    private fun eyeOpenness(
        face: VNFaceObservation,
        buffer: CVPixelBufferRef,
        orientation: FrameOrientation,
    ): EyePair? {
        val landmarks = face.landmarks ?: return null
        val left = landmarks.leftEye ?: return null
        val right = landmarks.rightEye ?: return null
        val scale = faceBoxPixelSize(face, buffer, orientation)
        val leftEar = aspectRatio(left, scale) ?: return null
        val rightEar = aspectRatio(right, scale) ?: return null
        return EyePair(left = leftEar.toOpenness(), right = rightEar.toOpenness())
    }

    private data class EyePair(val left: Float, val right: Float)

    private fun Float.toOpenness(): Float =
        ((this - EAR_CLOSED) / (EAR_OPEN - EAR_CLOSED)).coerceIn(0f, 1f)

    /**
     * Eye aspect ratio, order-independent.
     *
     * The textbook EAR formula indexes specific landmark points, which requires
     * knowing the order Vision emits them in — and that order is a property of
     * the request revision, not of the API, so an SDK that hard-codes it breaks
     * on an OS update with a subtle wrong number rather than a crash. This
     * computes the same quantity geometrically instead: the widest pair of
     * points is the eye's corner-to-corner axis, and the aperture is how far the
     * remaining points sit off that axis on either side.
     *
     * [scale] converts Vision's box-normalised points into pixels. Skipping that
     * step is the classic bug here: the points are normalised to the *face
     * bounding box*, which is not square, so a ratio computed in normalised
     * space silently tracks the box's aspect instead of the eyelids'.
     */
    private fun aspectRatio(region: VNFaceLandmarkRegion2D, scale: Size): Float? {
        val count = region.pointCount.toInt()
        if (count < MIN_EYE_POINTS) return null
        val points = region.normalizedPoints ?: return null

        val xs = FloatArray(count)
        val ys = FloatArray(count)
        for (i in 0 until count) {
            val p: CGPoint = points[i]
            xs[i] = (p.x * scale.width).toFloat()
            ys[i] = (p.y * scale.height).toFloat()
        }

        var ax = 0
        var bx = 1
        var widest = -1f
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val d = hypot(xs[i] - xs[j], ys[i] - ys[j])
                if (d > widest) {
                    widest = d
                    ax = i
                    bx = j
                }
            }
        }
        if (widest <= 0f) return null

        // Unit vector along the corner-to-corner axis, then the signed distance
        // of every point from it. Upper and lower lids land on opposite signs,
        // so their extremes sum to the full aperture.
        val ux = (xs[bx] - xs[ax]) / widest
        val uy = (ys[bx] - ys[ax]) / widest
        var above = 0f
        var below = 0f
        for (i in 0 until count) {
            val perpendicular = (xs[i] - xs[ax]) * -uy + (ys[i] - ys[ax]) * ux
            if (perpendicular > above) above = perpendicular
            if (perpendicular < below) below = perpendicular
        }
        return (above - below) / widest
    }

    private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    /** The face box in pixels of the upright image, for isotropic landmark scaling. */
    private fun faceBoxPixelSize(
        face: VNFaceObservation,
        buffer: CVPixelBufferRef,
        orientation: FrameOrientation,
    ): Size {
        val upright = uprightSize(buffer, orientation)
        return face.boundingBox.useContents {
            Size(
                width = max(1.0, size.width * upright.width),
                height = max(1.0, size.height * upright.height),
            )
        }
    }

    private companion object {
        /**
         * Vision's yaw axis relative to [FaceFrame]'s, for a non-mirrored image.
         *
         * The single hard-coded sign in the iOS pose path. See the class KDoc for
         * the on-device check that confirms it.
         */
        const val YAW_SIGN = -1f

        /** Vision reports chin-up as positive, which already matches. */
        const val PITCH_SIGN = 1f

        /** Vision's roll is positive counter-clockwise in the image plane. */
        const val ROLL_SIGN = -1f

        /**
         * EAR of a shut eye. Below this, openness reads as 0.
         *
         * A guess until measured: see the class KDoc. Anything built on the
         * resulting numbers has to be calibrated per platform.
         */
        const val EAR_CLOSED = 0.10f

        /** EAR of a comfortably open eye. Above this, openness reads as 1. */
        const val EAR_OPEN = 0.30f

        /** Vision's eye regions carry six or eight points; fewer is not an eye. */
        const val MIN_EYE_POINTS = 4
    }
}

/** A width/height pair in pixels, used for landmark scaling. */
internal data class Size(val width: Double, val height: Double)

/**
 * This orientation as ImageIO's `CGImagePropertyOrientation`.
 *
 * cinterop imports that C enum as a plain `UInt` typealias rather than a Kotlin
 * enum, so the EXIF numbers on [FrameOrientation] *are* the ImageIO values and
 * the conversion is an identity. Kept as a named function anyway so the two
 * vocabularies stay distinguishable at call sites: passing a raw `1u` to Vision
 * and hoping it means "upright" is how a pipeline ends up analysing sideways
 * frames.
 */
internal fun FrameOrientation.toCoreGraphics(): CGImagePropertyOrientation = exifValue
