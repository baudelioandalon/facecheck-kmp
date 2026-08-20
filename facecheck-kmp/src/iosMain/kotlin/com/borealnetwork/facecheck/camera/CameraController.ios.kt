package com.borealnetwork.facecheck.camera

import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.liveness.CapturedJpeg
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.position
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.NSError
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

/**
 * On iOS the camera needs the view controller that will own its preview layer.
 *
 * @property viewController the controller presenting the liveness UI. The
 *   capture session attaches its `AVCaptureVideoPreviewLayer` to this
 *   controller's view and follows its lifecycle.
 */
actual class CameraHost(val viewController: UIViewController)

/**
 * The default iOS camera: `AVCaptureSession`, front camera, Vision face detection.
 *
 * Two outputs hang off one session, because they answer two different questions:
 *
 *  - [AVCaptureVideoDataOutput] drives the analysis stream, with
 *    `alwaysDiscardsLateVideoFrames = true`. A liveness session cares what the
 *    face is doing *now*; a queue of buffers the detector fell behind on would
 *    have the machine scoring a turn the user finished a second ago and coaching
 *    them against a stale reality.
 *  - [AVCapturePhotoOutput] takes the one still that actually gets uploaded. It
 *    exists separately because the video stream is the wrong source for it:
 *    720p video buffers are noisier and lower-resolution than a real photo
 *    capture, and the accuracy of the whole system rests on that single image.
 *    The video path stays as a fallback for hosts (and simulators) with no
 *    usable photo output.
 *
 * ### Portrait
 *
 * Both connections are pinned to portrait, so buffers arrive upright and the
 * detector is handed [FrameOrientation.UP]. That is a deliberate limitation
 * rather than an oversight: a liveness UI is portrait, and pinning here means
 * neither the pose maths nor the still encoding has to track device rotation —
 * a whole class of "works in portrait, finds no face in landscape" bugs that
 * only ever show up on someone else's device.
 *
 * ### Mirroring
 *
 * The preview is mirrored when [CameraOptions.mirrorPreview] says so, because a
 * user watching an un-mirrored preview turns the wrong way. Neither the analysis
 * connection nor the photo connection is mirrored, which is what lets
 * [VisionFaceDetector] know the sign of what it is reading and what keeps the
 * uploaded reference photo from being a mirror image. See
 * [CameraController.captureStill].
 *
 * ### Threading
 *
 * Everything touching the session runs on one private serial queue. Configuring
 * an `AVCaptureSession` on the main thread blocks it for long enough to drop
 * frames in the host's own UI, and `startRunning()` in particular can take
 * hundreds of milliseconds.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCameraController internal constructor(
    private val host: CameraHost,
    private val options: CameraOptions,
    private val detector: FaceDetectorBridge,
) : CameraController {

    private val session = AVCaptureSession()
    private val sessionQueue = dispatch_queue_create("com.borealnetwork.facecheck.capture", null)
    private val videoOutput = AVCaptureVideoDataOutput()
    private val photoOutput = AVCapturePhotoOutput()

    /**
     * Hot, and it drops rather than queues.
     *
     * `DROP_OLDEST` over a one-frame buffer is the flow-level counterpart of
     * `alwaysDiscardsLateVideoFrames`: a slow collector costs frame rate, never
     * latency. `tryEmit` from the capture callback then always succeeds, so the
     * camera thread never blocks on a consumer.
     */
    private val _frames = MutableSharedFlow<FaceFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Surfaces a failure to open the camera *through the frame stream*.
     *
     * Every reason the camera can fail to open here — a denied permission, a
     * device with no front camera, an input the OS refuses to hand over — is
     * discovered asynchronously on [sessionQueue], long after [start] returned.
     * Logging it and returning would leave `runLivenessSession` collecting a
     * flow that never emits: the machine stays in `Idle`, `onTick` returns early
     * in that state, and the user stares at a black screen for the full session
     * timeout before being told the verification "took too long". Collecting
     * [frames] rethrows this immediately instead, so the session ends with
     * `CAMERA_UNAVAILABLE` and a message about camera permissions. This mirrors
     * `AndroidCameraController`, which has the same channel for the same reason.
     */
    private val _failures = MutableSharedFlow<FaceCheckException>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val frames: Flow<FaceFrame> = merge(
        _frames.asSharedFlow(),
        _failures.map<FaceCheckException, FaceFrame> { throw it },
    )

    /**
     * The layer showing the camera to the user.
     *
     * Public and built eagerly — before the session is configured, let alone
     * running — because the host has to lay it out. A `CALayer` does not
     * participate in Auto Layout, so its frame has to be reset from the view
     * controller's `viewDidLayoutSubviews`, and a layer the host cannot reach is
     * a layer that is the wrong size on every device but the one it was written
     * on. [start] adds it to the host view; [stop] removes it.
     */
    val previewLayer: AVCaptureVideoPreviewLayer =
        AVCaptureVideoPreviewLayer(session = session).apply {
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }

    private var previewAttached = false
    private var configured = false
    private var running = false

    /** Held so `AVCapturePhotoOutput` never outlives the delegate mid-capture. */
    private var photoDelegate: PhotoDelegate? = null

    /** Set when a still is wanted from the analysis stream; see [captureFromStream]. */
    private var stillFromStream: ((CapturedJpeg?) -> Unit)? = null

    private val sampleDelegate = SampleDelegate(this)

    // --- CameraController -----------------------------------------------------

    // resetReplayCache() is the only way to drop a stale failure without
    // rebuilding the flow, and it has been stable-in-practice for years.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        // Cleared synchronously, before anything is dispatched. A failure from a
        // previous attempt is replayed to every new collector, and
        // `runLivenessSession` subscribes the moment start() returns — clearing
        // it on the session queue instead would leave a window where the retry
        // that was going to succeed dies on the old error. The common case is
        // the user granting the camera permission in Settings and coming back.
        _failures.resetReplayCache()

        dispatch_async(sessionQueue) {
            if (running) return@dispatch_async
            when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
                AVAuthorizationStatusAuthorized -> startConfigured()
                AVAuthorizationStatusNotDetermined ->
                    // Asking here rather than refusing outright: a host that has
                    // not prompted yet still works, it just starts a beat later.
                    // A host that wants the prompt at a sensible moment in its
                    // own flow should ask before calling start().
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                        if (granted) {
                            dispatch_async(sessionQueue) { startConfigured() }
                        } else {
                            FaceCheckLogger.error { "camera permission denied by the user" }
                            fail(PERMISSION_MESSAGE)
                        }
                    }
                else -> {
                    FaceCheckLogger.error {
                        "camera permission is denied or restricted; check NSCameraUsageDescription"
                    }
                    fail(PERMISSION_MESSAGE)
                }
            }
        }
    }

    override fun stop() {
        dispatch_async(sessionQueue) {
            if (running) {
                session.stopRunning()
                running = false
            }
            detector.close()
            // Fail any capture still waiting on a frame that will never arrive,
            // rather than leaving the caller suspended until the session-wide
            // timeout fires.
            stillFromStream?.invoke(null)
            stillFromStream = null
        }
        dispatch_async(dispatch_get_main_queue()) {
            previewLayer.removeFromSuperlayer()
            previewAttached = false
        }
    }

    override suspend fun captureStill(): CapturedJpeg {
        capturePhoto()?.let { return it }
        FaceCheckLogger.warn { "photo output unavailable; falling back to the analysis stream" }
        captureFromStream()?.let { return it }
        throw FaceCheckException(
            code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
            message = "No se pudo tomar la foto. Revisa los permisos de cámara e " +
                "intenta de nuevo.",
        )
    }

    // --- Session set-up -------------------------------------------------------

    /** Runs on [sessionQueue]. */
    private fun startConfigured() {
        if (!configured) {
            val failure = configure()
            if (failure != null) {
                fail(failure)
                return
            }
            configured = true
        }
        if (!running) {
            session.startRunning()
            running = true
        }
    }

    /**
     * Runs on [sessionQueue].
     *
     * @return null once the session can capture, or the failure to hand to the
     *   collector. Returning the exception rather than a boolean is deliberate:
     *   a `false` here used to mean "logged something and gave up", and every
     *   new early return was one more way for the session to die by timeout
     *   instead of by a message the user can act on.
     */
    @OptIn(BetaInteropApi::class)
    private fun configure(): FaceCheckException? {
        val device = frontCamera()
        if (device == null) {
            // The simulator has no camera at all, and this is the message a
            // developer sees first when they run the demo there.
            FaceCheckLogger.error { "no front camera on this device" }
            return cameraUnavailable(
                "Este dispositivo no tiene una cámara que podamos usar para la verificación.",
            )
        }

        val input = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
            if (created == null) {
                FaceCheckLogger.error {
                    "cannot open the camera: ${error.value?.localizedDescription}"
                }
            }
            created
        } ?: return cameraUnavailable(
            "No se pudo abrir la cámara. Ciérrala en otras apps e intenta de nuevo.",
        )

        session.beginConfiguration()
        // 720p rather than the highest available: the analysis path resamples to
        // a fraction of this anyway, and a 4K stream costs battery and thermal
        // headroom that a 30-second session cannot spend. The still comes from
        // the photo output, at full sensor resolution, so nothing is lost.
        if (session.canSetSessionPreset(AVCaptureSessionPreset1280x720)) {
            session.sessionPreset = AVCaptureSessionPreset1280x720
        }
        if (session.canAddInput(input)) session.addInput(input)

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(sampleDelegate, sessionQueue)
        if (session.canAddOutput(videoOutput)) session.addOutput(videoOutput)
        if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
        session.commitConfiguration()

        pinToPortrait(videoOutput.connectionWithMediaType(AVMediaTypeVideo))
        pinToPortrait(photoOutput.connectionWithMediaType(AVMediaTypeVideo))
        attachPreview()
        return null
    }

    /** Push a camera failure at whoever is collecting [frames]. */
    private fun fail(message: String) {
        fail(cameraUnavailable(message))
    }

    private fun fail(failure: FaceCheckException) {
        // tryEmit always succeeds on a DROP_OLDEST buffer, so the capture queue
        // never blocks on a collector that may not exist yet.
        _failures.tryEmit(failure)
    }

    private fun cameraUnavailable(message: String) = FaceCheckException(
        code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
        message = message,
    )

    /**
     * Upright, un-mirrored buffers.
     *
     * `videoOrientation` is deprecated in favour of `videoRotationAngle`, and is
     * used anyway: the replacement is iOS 17+, calling it on anything older is
     * an unrecognised-selector crash, and a `respondsToSelector` dance around
     * two lines that the deprecated API still performs correctly buys nothing.
     */
    @Suppress("DEPRECATION")
    private fun pinToPortrait(connection: AVCaptureConnection?) {
        val target = connection ?: return
        if (target.isVideoOrientationSupported()) {
            target.videoOrientation = AVCaptureVideoOrientationPortrait
        }
        if (target.isVideoMirroringSupported()) {
            target.automaticallyAdjustsVideoMirroring = false
            target.videoMirrored = false
        }
    }

    @Suppress("DEPRECATION")
    private fun attachPreview() {
        dispatch_async(dispatch_get_main_queue()) {
            if (previewAttached) return@dispatch_async
            // Reading `.view` is what forces the controller to load it, so this
            // is safe even when start() runs before the controller is on screen.
            val view = host.viewController.view
            previewLayer.setFrame(view.bounds)
            // Only the preview is mirrored. A user looking at an un-mirrored
            // preview turns their head the wrong way when told to turn left,
            // which reads to them as the SDK being broken.
            previewLayer.connection?.let { connection ->
                if (connection.isVideoOrientationSupported()) {
                    connection.videoOrientation = AVCaptureVideoOrientationPortrait
                }
                if (connection.isVideoMirroringSupported()) {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.videoMirrored = options.mirrorPreview
                }
            }
            view.layer.insertSublayer(previewLayer, atIndex = 0u)
            previewAttached = true
        }
    }

    private fun frontCamera(): AVCaptureDevice? {
        val wanted = if (options.useFrontCamera) {
            AVCaptureDevicePositionFront
        } else {
            AVCaptureDevicePositionBack
        }
        val discovery = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            mediaType = AVMediaTypeVideo,
            position = wanted,
        )
        val devices = discovery.devices.filterIsInstance<AVCaptureDevice>()
        // The discovery session already filtered by position; the second check is
        // belt and braces, and the fallback covers a device that reports an
        // unspecified position rather than leaving the user with a black screen.
        return devices.firstOrNull { it.position == wanted } ?: devices.firstOrNull()
    }

    // --- Frame delivery -------------------------------------------------------

    /** Called on [sessionQueue], one buffer at a time. */
    internal fun onSampleBuffer(sampleBuffer: CMSampleBufferRef?) {
        val sample = sampleBuffer ?: return
        val buffer: CVPixelBufferRef = CMSampleBufferGetImageBuffer(sample) ?: return

        // Presentation timestamps come from the capture clock and are monotonic
        // across the session, which is exactly what ChallengeMachine needs: a
        // wall clock would let a system time change end a session mid-turn.
        val seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
        val timestampMs = (seconds * 1000.0).toLong()

        // The connections are pinned to portrait, so AVFoundation has already
        // rotated the buffer and the detector needs no further hint.
        detector.analyze(sample as COpaquePointer, FrameOrientation.UP, timestampMs)
            ?.let { _frames.tryEmit(it) }

        stillFromStream?.let { resume ->
            stillFromStream = null
            resume(
                encodePixelBufferAsJpeg(
                    buffer = buffer,
                    orientation = FrameOrientation.UP,
                    mirrored = false,
                    maxEdge = options.targetStillSize,
                    quality = options.jpegQuality,
                ),
            )
        }
    }

    // --- Still capture --------------------------------------------------------

    // The Kotlin Map -> NSDictionary bridge the photo settings need is still
    // marked beta by cinterop; the alternative is building an NSMutableDictionary
    // by hand, which is the same bridge with more code.
    @OptIn(BetaInteropApi::class)
    private suspend fun capturePhoto(): CapturedJpeg? = suspendCancellableCoroutine { cont ->
        dispatch_async(sessionQueue) {
            if (!running || photoOutput.connectionWithMediaType(AVMediaTypeVideo) == null) {
                cont.resume(null)
                return@dispatch_async
            }
            val settings = AVCapturePhotoSettings.photoSettingsWithFormat(
                mapOf(AVVideoCodecKey to AVVideoCodecTypeJPEG),
            )
            val delegate = PhotoDelegate { data ->
                photoDelegate = null
                cont.resume(
                    data?.let { reencodeJpeg(it, options.targetStillSize, options.jpegQuality) },
                )
            }
            photoDelegate = delegate
            photoOutput.capturePhotoWithSettings(settings, delegate)
        }
    }

    /**
     * Take the still from the next analysis frame instead.
     *
     * Bounded by its own timeout because the alternative is worse than failing:
     * without a camera there is no next frame, and a caller would sit suspended
     * until the session-wide deadline rather than getting a camera error it can
     * show the user.
     */
    private suspend fun captureFromStream(): CapturedJpeg? = withTimeoutOrNull(STREAM_STILL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            dispatch_async(sessionQueue) {
                if (!running) {
                    cont.resume(null)
                    return@dispatch_async
                }
                stillFromStream = { bytes -> cont.resume(bytes) }
            }
            cont.invokeOnCancellation {
                dispatch_async(sessionQueue) { stillFromStream = null }
            }
        }
    }

    private companion object {
        /** Long enough for several frames at any frame rate the SDK will see. */
        const val STREAM_STILL_TIMEOUT_MS = 2_000L

        /**
         * Shown for every denied/restricted permission outcome.
         *
         * It points at Settings rather than offering a retry because iOS only
         * ever prompts once: after the first refusal `requestAccessForMediaType`
         * returns false without showing anything, so a user who taps "intentar
         * de nuevo" would see the same black screen forever.
         */
        const val PERMISSION_MESSAGE =
            "Necesitamos permiso para usar la cámara. Actívalo en Ajustes > " +
                "Privacidad > Cámara e intenta de nuevo."
    }
}

/**
 * Bridges the Objective-C sample-buffer callback back into Kotlin.
 *
 * A separate object rather than making the controller itself the delegate:
 * `AVCaptureVideoDataOutput` holds its delegate weakly, and an
 * `NSObject`-derived controller would tie the SDK's public class to an
 * Objective-C lifetime that Kotlin cannot see.
 */
@OptIn(ExperimentalForeignApi::class)
private class SampleDelegate(
    private val controller: IosCameraController,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        controller.onSampleBuffer(didOutputSampleBuffer)
    }
}

/** One-shot photo delegate; [onResult] receives null on any capture failure. */
@OptIn(ExperimentalForeignApi::class)
private class PhotoDelegate(
    private val onResult: (platform.Foundation.NSData?) -> Unit,
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

    private var delivered = false

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        // AVFoundation can call back more than once on some error paths, and
        // resuming a continuation twice is a crash rather than a warning.
        if (delivered) return
        delivered = true
        if (error != null) {
            FaceCheckLogger.error { "photo capture failed: ${error.localizedDescription}" }
            onResult(null)
            return
        }
        onResult(didFinishProcessingPhoto.fileDataRepresentation())
    }
}

/**
 * The default camera pipeline for iOS.
 *
 * Uses [VisionFaceDetector], which needs no dependency beyond Apple's own
 * frameworks. To get exact parity with Android's ML Kit detector — calibrated
 * eye-open probabilities and a real tracking id — implement [FaceDetectorBridge]
 * in Swift and build the controller with [createCameraController] below.
 */
actual fun createCameraController(
    host: CameraHost,
    options: CameraOptions,
): CameraController = IosCameraController(host, options, VisionFaceDetector(mirrored = false))

/**
 * The same pipeline with a detector of your own.
 *
 * The escape hatch for hosts that want ML Kit, or any other detector, without
 * the SDK linking it: see [FaceDetectorBridge] for what the implementation owes
 * the session, and `docs/IOS.md` for a worked Swift example.
 */
fun createCameraController(
    host: CameraHost,
    detector: FaceDetectorBridge,
    options: CameraOptions = CameraOptions(),
): CameraController = IosCameraController(host, options, detector)
