package com.borealnetwork.facecheck.camera

import android.content.Context

/**
 * On Android the camera hangs off a `Context`.
 *
 * A wrapper rather than an `actual typealias` to `Context`: a typealias would
 * make the `expect` class's modality (`final`) disagree with `Context`'s
 * (`abstract`) and fail to compile. Wrapping also keeps both platforms' call
 * sites the same shape — `CameraHost(activity)` here, `CameraHost(controller)`
 * on iOS.
 *
 * @property context an `Activity` context, not the application context: CameraX
 *   binds to a `LifecycleOwner`, and the application context has no lifecycle to
 *   bind to. Any `ContextWrapper` around an Activity works too.
 */
actual class CameraHost(val context: Context)

/**
 * CameraX + ML Kit, wired up the way the SDK expects.
 *
 * The concrete type is [AndroidCameraController], and a host app that wants a
 * preview has to say so:
 *
 * ```kotlin
 * val camera = createCameraController(CameraHost(activity)) as AndroidCameraController
 * camera.attachPreview(previewView)
 * ```
 *
 * The cast is the price of an `expect fun` whose return type has to be the
 * common interface on every platform. Constructing [AndroidCameraController]
 * directly is equally supported and reads better in Android-only code — this
 * function exists for code that is shared with iOS.
 *
 * Whoever creates it owns it: call [AndroidCameraController.close] when the
 * screen goes away. The SDK's own [CameraController.stop] releases the camera
 * but deliberately not the ML Kit detector, because a screen usually runs more
 * than one session.
 *
 * @throws com.borealnetwork.facecheck.model.FaceCheckException
 *   [CAMERA_UNAVAILABLE][com.borealnetwork.facecheck.model.FaceCheckErrorCode.CAMERA_UNAVAILABLE]
 *   if [host] does not wrap a `LifecycleOwner`.
 */
actual fun createCameraController(
    host: CameraHost,
    options: CameraOptions,
): CameraController = AndroidCameraController(host = host, options = options)