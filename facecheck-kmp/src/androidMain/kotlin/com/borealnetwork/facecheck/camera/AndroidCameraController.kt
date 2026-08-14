package com.borealnetwork.facecheck.camera

import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The default Android capture pipeline: CameraX for pixels, ML Kit for faces.
 *
 * ```kotlin
 * val camera = createCameraController(CameraHost(activity)) as AndroidCameraController
 * camera.attachPreview(previewView)          // optional: the SDK never needs it
 * val result = FaceCheck.verify(email, camera, machine)
 * camera.close()                             // on the way out of the screen
 * ```
 *
 * ### Shape of it
 *
 * `ImageAnalysis` in [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] feeds the ML Kit
 * detector; every detection becomes one [FaceFrame] on [frames]. `ImageCapture`
 * takes the one still at the end. `Preview` exists only if a host app asked for
 * it — a liveness session is perfectly capable of running with the screen off,
 * and forcing a preview use case would cost a stream on devices whose camera
 * can only bind two.
 *
 * The dropping is not incidental. [frames] drops rather than queues under back
 * pressure because the machine is scoring what the face is doing *now*: a
 * backlog would have it grading a movement the user finished a second ago, and
 * on a slow device that is the difference between "turn left" passing and
 * timing out.
 *
 * ### Lifecycle
 *
 * [start] and [stop] are idempotent and cheap to call repeatedly; the SDK calls
 * both around every session and a host app may call [start] earlier to get a
 * live preview before the user presses anything. Neither one releases the
 * detector — that is [close], and it must be called when the screen goes away,
 * or the ML Kit detector and the analysis thread outlive it.
 *
 * ### Threading
 *
 * Detection runs on one private background thread. CameraX binding happens on
 * the main thread, posted there internally, so [start] and [stop] are safe to
 * call from anywhere. [frames] emits from the analysis thread.
 *
 * @param host an `Activity` (or any `Context` wrapping a `LifecycleOwner`).
 *   CameraX binds to a lifecycle and the application context has none.
 * @param mirroring whether frames reach the detector mirrored. Leave it alone
 *   unless you are certain — read [AnalysisMirroring] first, it decides which
 *   way "gira a la izquierda" means.
 */
class AndroidCameraController(
    host: CameraHost,
    private val options: CameraOptions = CameraOptions(),
    private val mirroring: AnalysisMirroring = AnalysisMirroring.NONE,
) : CameraController, Closeable {

    private val context: Context = host.context.applicationContext
    private val lifecycleOwner: LifecycleOwner = host.context.findLifecycleOwner()
    private val mainExecutor = ContextCompat.getMainExecutor(host.context)

    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FaceCheck-analysis").apply { isDaemon = true }
        }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // FAST over ACCURATE: the machine only reads Euler angles and a box,
            // and ACCURATE buys landmark precision at roughly triple the latency.
            // Frame rate is what a liveness session actually needs — a turn that
            // is sampled four times a second reads as a teleport.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // The only reason classification is on: eye-open probabilities, so a
            // host app can avoid capturing the still mid-blink.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // Tracking ids are the session's only continuity signal; without
            // them ChallengeMachine cannot notice one person being swapped for
            // another between challenges.
            .enableTracking()
            // Shared with VisionFaceDetector on iOS; see MIN_FACE_WIDTH_RATIO.
            .setMinFaceSize(MIN_FACE_WIDTH_RATIO)
            .build(),
    )

    private val _frames = MutableSharedFlow<FaceFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Surfaces a failure to open the camera *through the frame stream*.
     *
     * CameraX reports binding failures asynchronously, long after [start]
     * returned. Without this the session would sit in `Positioning` waiting for
     * frames from a camera that never opened and fail ninety seconds later with
     * a timeout, which tells the user nothing. Collecting [frames] rethrows it
     * immediately instead, and the session ends with `CAMERA_UNAVAILABLE`.
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

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile
    private var preview: Preview? = null

    @Volatile
    private var surfaceProvider: Preview.SurfaceProvider? = null

    /** The PreviewView whose output coordinates the optional guide receives. */
    @Volatile
    private var previewView: PreviewView? = null

    /** Main-thread-only deferred bind awaiting a PreviewView layout. */
    private var pendingPreviewLayout: PendingPreviewLayout? = null

    /**
     * Optional guide enforcement supplied by a host app.
     *
     * Its input is the face box after CameraX maps it into the exact visible
     * PreviewView pixel coordinates. Leaving this unset retains the historical
     * `insideGuide = true` behavior for every host that does not draw a guide.
     */
    @Volatile
    private var previewFaceGuide: ((RectF) -> Boolean)? = null

    @OptIn(TransformExperimental::class)
    private val imageProxyTransformFactory = ImageProxyTransformFactory().apply {
        setUsingRotationDegrees(true)
    }

    /** Last `(faceCount, trackingId)` logged; see [logDetectionChange]. */
    @Volatile
    private var lastDetection: Pair<Int, Int?>? = null

    // --- Host-app extras (not part of the common CameraController) -------------

    /**
     * Route the camera preview into [view].
     *
     * Optional, and safe to call before or after [start]: if the camera is
     * already bound the provider is handed over on the spot, otherwise it is
     * picked up by the next bind.
     *
     * Note that `PreviewView` mirrors the front camera by itself, and the
     * detector never sees that mirroring — which is the whole subject of
     * [AnalysisMirroring].
     */
    fun attachPreview(view: PreviewView) {
        previewView = view
        if (Looper.myLooper() == Looper.getMainLooper()) {
            configurePreview(view)
        } else {
            mainExecutor.execute { configurePreview(view) }
        }
    }

    /** Stop drawing into whatever [attachPreview] was given. */
    fun detachPreview() {
        previewView = null
        clearPendingPreviewLayout()
        setSurfaceProvider(null)
    }

    /**
     * Install or remove a gate for faces drawn in [PreviewView] coordinates.
     *
     * The controller maps ML Kit's face box from each live [ImageProxy] into
     * the attached preview before invoking [guide]. A null guide deliberately
     * preserves the default `FaceFrame.insideGuide = true` behavior.
     */
    fun setPreviewFaceGuide(guide: ((RectF) -> Boolean)?) {
        previewFaceGuide = guide
    }

    /** Configure the view before its surface provider reaches a CameraX [Preview]. */
    private fun configurePreview(view: PreviewView) {
        if (previewView !== view) return
        clearPendingPreviewLayoutOnMain()
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        setSurfaceProvider(view.surfaceProvider)
        if (running.get()) cameraProvider?.let(::bind)
    }

    private fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        surfaceProvider = provider
        mainExecutor.execute { preview?.setSurfaceProvider(provider) }
    }

    // --- CameraController -----------------------------------------------------

    // resetReplayCache() is the only way to drop a stale failure without
    // rebuilding the flow, and it has been stable-in-practice for years.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (closed.get()) {
            throw FaceCheckException(
                code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
                message = "Este controlador de cámara ya fue liberado.",
            )
        }
        if (!running.compareAndSet(false, true)) return

        // A failure from a previous session is replayed to every new collector,
        // which would otherwise kill a retry that was going to succeed — the
        // common case being the user granting the camera permission and pressing
        // the button again.
        _failures.resetReplayCache()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                // stop() may have won the race while the provider was resolving.
                if (!running.get()) return@addListener
                try {
                    bind(future.get())
                } catch (failure: Exception) {
                    running.set(false)
                    FaceCheckLogger.error { "no se pudo abrir la cámara: ${failure.message}" }
                    _failures.tryEmit(
                        FaceCheckException(
                            code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
                            message = "No se pudo abrir la cámara. Revisa los permisos " +
                                "de la aplicación e intenta de nuevo.",
                            cause = failure,
                        ),
                    )
                }
            },
            mainExecutor,
        )
    }

    override fun stop() {
        clearPendingPreviewLayout()
        if (!running.compareAndSet(true, false)) return
        mainExecutor.execute {
            imageAnalysis?.clearAnalyzer()
            runCatching { cameraProvider?.unbindAll() }
                .onFailure { FaceCheckLogger.warn { "unbind falló: ${it.message}" } }
            imageAnalysis = null
            imageCapture = null
            preview = null
        }
    }

    override suspend fun captureStill(): ByteArray = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(
                FaceCheckException(
                    code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
                    message = "La cámara no está lista para tomar la foto.",
                ),
            )
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val jpeg = StillEncoder.encode(image, options)
                        FaceCheckLogger.debug {
                            "still capturado: ${FaceCheckLogger.describeBytes(jpeg.size)} " +
                                "rotación=${image.imageInfo.rotationDegrees}°"
                        }
                        if (continuation.isActive) continuation.resume(jpeg)
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        FaceCheckException(
                            code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
                            message = "No se pudo tomar la foto. Intenta de nuevo.",
                            cause = exception,
                        ),
                    )
                }
            },
        )
    }

    /**
     * Release the detector and the analysis thread. Idempotent.
     *
     * Separate from [stop] because the SDK calls [stop] after every session and
     * a screen typically runs several.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clearPendingPreviewLayout()
        stop()
        detector.close()
        analysisExecutor.shutdown()
    }

    // --- Binding --------------------------------------------------------------

    private fun bind(provider: ProcessCameraProvider) {
        cameraProvider = provider

        val selector = if (options.useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply { setAnalyzer(analysisExecutor, FaceAnalyzer()) }

        val capture = ImageCapture.Builder()
            // MINIMIZE_LATENCY over MAXIMIZE_QUALITY: the shutter fires at the end
            // of a liveness session, on a face that is holding still by
            // instruction, and a slow capture is a capture of a user who already
            // started moving again.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val attachedPreview = previewView
        val previewUseCase = surfaceProvider?.let { surface ->
            Preview.Builder().build().apply { setSurfaceProvider(surface) }
        }

        // PreviewView cannot expose a usable ViewPort until its first layout.
        // Defer the first binding rather than bind independent use cases and
        // accidentally map analysis coordinates into a different crop.
        val viewPort = attachedPreview?.viewPort
        if (previewUseCase != null && attachedPreview != null && viewPort == null) {
            bindWhenPreviewViewportIsReady(provider, attachedPreview)
            return
        }

        provider.unbindAll()
        if (previewUseCase != null && viewPort != null) {
            val useCaseGroup = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(previewUseCase)
                .addUseCase(analysis)
                .addUseCase(capture)
                .build()
            provider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
        } else {
            // No preview remains a supported host configuration. It has no
            // PreviewView coordinate space, so keep the historical two-use-case bind.
            provider.bindToLifecycle(lifecycleOwner, selector, analysis, capture)
        }

        imageAnalysis = analysis
        imageCapture = capture
        preview = previewUseCase
        FaceCheckLogger.info {
            "cámara abierta (${if (options.useFrontCamera) "frontal" else "trasera"}), " +
                "preview=${previewUseCase != null}, mirroring=$mirroring"
        }
    }

    /** Re-run binding once [PreviewView.viewPort] is available after layout. */
    private fun bindWhenPreviewViewportIsReady(
        provider: ProcessCameraProvider,
        view: PreviewView,
    ) {
        clearPendingPreviewLayoutOnMain()
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                changedView: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (view.viewPort == null) return
                if (!consumePendingPreviewLayout(view, this)) return
                if (running.get() && !closed.get() && previewView === view) bind(provider)
            }
        }
        pendingPreviewLayout = PendingPreviewLayout(view, listener)
        view.addOnLayoutChangeListener(listener)
        if (view.viewPort != null) {
            if (
                consumePendingPreviewLayout(view, listener) &&
                running.get() &&
                !closed.get() &&
                previewView === view
            ) {
                bind(provider)
            }
        }
    }

    /** Remove the single pending layout callback without retaining a closed capture. */
    private fun clearPendingPreviewLayout() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearPendingPreviewLayoutOnMain()
        } else {
            mainExecutor.execute(::clearPendingPreviewLayoutOnMain)
        }
    }

    /** Main-thread counterpart of [clearPendingPreviewLayout]. */
    private fun clearPendingPreviewLayoutOnMain() {
        pendingPreviewLayout?.let { pending ->
            pending.view.removeOnLayoutChangeListener(pending.listener)
        }
        pendingPreviewLayout = null
    }

    /** Consume [listener] only when it is still the currently pending callback. */
    private fun consumePendingPreviewLayout(
        view: PreviewView,
        listener: View.OnLayoutChangeListener,
    ): Boolean {
        val pending = pendingPreviewLayout
        view.removeOnLayoutChangeListener(listener)
        if (pending?.view !== view || pending.listener !== listener) return false
        pendingPreviewLayout = null
        return true
    }

    private data class PendingPreviewLayout(
        val view: PreviewView,
        val listener: View.OnLayoutChangeListener,
    )

    // --- Analysis -------------------------------------------------------------

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {

        @ExperimentalGetImage
        override fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return
            }

            val rotation = proxy.imageInfo.rotationDegrees
            val timestampMs = proxy.monotonicMs()
            val input = InputImage.fromMediaImage(media, rotation)

            detector.process(input)
                // On the analysis executor, not the default main thread: the
                // success listener is where the Y plane gets sampled, and doing
                // that on the UI thread would stutter the preview.
                .addOnSuccessListener(analysisExecutor) { faces ->
                    runCatching {
                        val frame = toFrame(faces, proxy, rotation, timestampMs)
                        logDetectionChange(frame)
                        _frames.tryEmit(frame)
                    }.onFailure {
                        FaceCheckLogger.warn { "no se pudo armar el frame: ${it.message}" }
                    }
                }
                .addOnFailureListener(analysisExecutor) { failure ->
                    FaceCheckLogger.warn { "ML Kit falló en un frame: ${failure.message}" }
                }
                // The proxy has to outlive the detection — ML Kit reads the
                // underlying Image asynchronously — and CameraX will not deliver
                // another frame until it is closed, so this is also the back
                // pressure. Exactly once, whatever happened.
                .addOnCompleteListener(analysisExecutor) { proxy.close() }
        }
    }

    private fun toFrame(
        faces: List<Face>,
        proxy: ImageProxy,
        rotation: Int,
        timestampMs: Long,
    ): FaceFrame {
        val guide = previewFaceGuide
        if (faces.isEmpty()) {
            return FrameGeometry.noFace(timestampMs).copy(insideGuide = guide == null)
        }

        // The largest face is the subject. With more than one in frame the
        // machine fails the session anyway, but it needs a real frame to do it.
        val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
            ?: return FrameGeometry.noFace(timestampMs)

        val (uprightWidth, uprightHeight) = FrameGeometry.uprightSize(proxy, rotation)
        val faceInBuffer = FrameGeometry.uprightRectToBuffer(
            rect = face.boundingBox,
            rotationDegrees = rotation,
            bufferWidth = proxy.width,
            bufferHeight = proxy.height,
        )

        val frame = FrameGeometry.frameOf(
            face = face,
            faceCount = faces.size,
            uprightWidth = uprightWidth,
            uprightHeight = uprightHeight,
            quality = LumaMetrics.measure(proxy, faceInBuffer),
            timestampMs = timestampMs,
            mirroring = mirroring,
        )
        return frame.copy(
            insideGuide = guide?.let { isInsidePreviewFaceGuide(proxy, face, it) } ?: true,
        )
    }

    /**
     * Map ML Kit's upright face box into the visible PreviewView before checking
     * the host-owned guide. Any unavailable transform or mapping failure blocks
     * the frame rather than claiming a face is inside a guide we could not see.
     */
    @OptIn(TransformExperimental::class)
    private fun isInsidePreviewFaceGuide(
        proxy: ImageProxy,
        face: Face,
        guide: (RectF) -> Boolean,
    ): Boolean = runCatching {
        val source = imageProxyTransformFactory.getOutputTransform(proxy)
        val target = previewOutputTransform()
        if (target == null) {
            false
        } else {
            val mappedFaceBounds = RectF(face.boundingBox)
            CoordinateTransform(source, target).mapRect(mappedFaceBounds)
            guide(mappedFaceBounds)
        }
    }.getOrElse { failure ->
        FaceCheckLogger.warn { "no se pudo mapear el rostro al preview: ${failure.message}" }
        false
    }

    /**
     * PreviewView checks its main-thread affinity, so obtain its nullable output
     * transform there even though face analysis is deliberately off the UI thread.
     */
    @OptIn(TransformExperimental::class)
    private fun previewOutputTransform(): OutputTransform? {
        val view = previewView ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return view.outputTransform

        val result = AtomicReference<OutputTransform?>()
        val completed = CountDownLatch(1)
        return try {
            mainExecutor.execute {
                try {
                    if (previewView === view) result.set(view.outputTransform)
                } finally {
                    completed.countDown()
                }
            }
            completed.await()
            result.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (failure: RuntimeException) {
            FaceCheckLogger.warn { "no se pudo obtener el transform del preview: ${failure.message}" }
            null
        }
    }

    /**
     * Log only when the detector's answer *changes*, never per frame.
     *
     * The two failures that are impossible to debug from a bug report —
     * `FACE_SWAPPED` and `MULTIPLE_FACES` — are both about tracking identity,
     * and identity is precisely what a log line per frame drowns. A line per
     * change gives the whole story of a session in a handful of rows: which id
     * the session bound to, when the detector dropped it, and what it came back
     * with.
     */
    private fun logDetectionChange(frame: FaceFrame) {
        val signature = frame.faceCount to frame.trackingId
        if (signature == lastDetection) return
        lastDetection = signature
        FaceCheckLogger.debug {
            "detección: rostros=${frame.faceCount} id=${frame.trackingId ?: "-"} " +
                "rostro=${(frame.faceRatio * 100).toInt()}% yaw=${frame.yaw.toInt()}° " +
                "nitidez=${frame.quality.sharpness.toInt()} luz=${frame.quality.brightness.toInt()}"
        }
    }

    /**
     * A monotonic millisecond stamp for the frame.
     *
     * CameraX reports nanoseconds in a timebase that varies by device, so the
     * value is only ever used as a difference — which is all
     * `ChallengeMachine` does with it. The fallback covers devices that report
     * zero rather than admitting they have no timestamp.
     */
    private fun ImageProxy.monotonicMs(): Long {
        val nanos = imageInfo.timestamp
        return if (nanos > 0L) nanos / 1_000_000L else SystemClock.elapsedRealtime()
    }
}

/**
 * Walk up the `ContextWrapper` chain looking for the lifecycle CameraX binds to.
 *
 * Needed because a `Context` handed in by a host app is frequently a themed
 * wrapper around the Activity rather than the Activity itself — Compose's
 * `LocalContext` inside a dialog being the usual one.
 */
private fun Context.findLifecycleOwner(): LifecycleOwner {
    var current: Context? = this
    while (current != null) {
        if (current is LifecycleOwner) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    throw FaceCheckException(
        code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
        message = "CameraHost necesita el Context de una Activity (un LifecycleOwner). " +
            "El contexto de la aplicación no sirve porque no tiene ciclo de vida.",
    )
}
