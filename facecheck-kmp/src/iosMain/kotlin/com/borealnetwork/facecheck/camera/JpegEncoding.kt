package com.borealnetwork.facecheck.camera

import com.borealnetwork.facecheck.liveness.CapturedJpeg
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Turning camera output into the JPEG the backend receives.
 *
 * Orientation is baked into pixels here, never written as an EXIF tag. The
 * backend decodes with OpenCV, and while `cv2.IMREAD_COLOR` does honour the tag,
 * half the tools that will ever touch these bytes — a debugging `imshow`, a
 * hand-rolled `np.frombuffer`, a reviewer opening the file in a viewer that
 * ignores EXIF — do not. A JPEG whose pixels are already upright is correct
 * under all of them, and the only cost is a re-encode that has to happen anyway
 * to reach `CameraOptions.targetStillSize`.
 */

/** The EXIF code for this orientation once the image is flipped horizontally. */
internal fun FrameOrientation.mirroredExifValue(): UInt = when (this) {
    // EXIF pairs each orientation with its mirror: up/upMirrored (1,2),
    // down/downMirrored (3,4), right/rightMirrored (6,7), left/leftMirrored (8,5).
    FrameOrientation.UP -> 2u
    FrameOrientation.DOWN -> 4u
    FrameOrientation.RIGHT -> 7u
    FrameOrientation.LEFT -> 5u
}

/**
 * Encode one camera frame as an upright, un-mirrored JPEG.
 *
 * The path a still takes when [AVCapturePhotoOutput][platform.AVFoundation.AVCapturePhotoOutput]
 * cannot run — which is every simulator, and any host that drives the analysis
 * stream without a photo output. Quality is lower than a real photo capture
 * (video buffers are smaller and noisier), so it is a fallback rather than the
 * default.
 *
 * @param mirrored whether [buffer] is horizontally flipped. The SDK turns
 *   mirroring off on its capture connections so this is false for its own path;
 *   it matters for a host feeding buffers from its own pipeline, where a
 *   mirrored front-camera connection is the default and forgetting it produces
 *   reference photos with the text on the user's t-shirt backwards.
 * @return null when Core Image cannot rasterise the buffer, which the caller
 *   must surface as a capture failure rather than an empty upload.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun encodePixelBufferAsJpeg(
    buffer: CVPixelBufferRef,
    orientation: FrameOrientation,
    mirrored: Boolean,
    maxEdge: Int,
    quality: Int,
): CapturedJpeg? {
    val exif = if (mirrored) orientation.mirroredExifValue() else orientation.exifValue
    val upright = CIImage(cVPixelBuffer = buffer).imageByApplyingOrientation(exif.toInt())
    val cgImage = sharedCiContext.createCGImage(upright, fromRect = upright.extent)
        ?: return null
    return UIImage.imageWithCGImage(cgImage).downscaledJpeg(maxEdge, quality)
}

/**
 * Re-encode `AVCapturePhoto.fileDataRepresentation()` at the SDK's size and quality.
 *
 * A full-resolution iPhone still is 3–5 MB and up to 4032 px on its long edge.
 * The backend caps uploads at 10 MB and its detector cannot use the extra
 * resolution — it downsamples before MTCNN either way — so shipping the original
 * would spend the user's mobile data on pixels that get thrown away, and would
 * put the request within a factor of two of a hard rejection for no benefit.
 *
 * Decoding through [UIImage] also normalises the EXIF rotation the photo output
 * writes: `drawInRect` applies `imageOrientation`, so what comes out is upright
 * pixels with no tag at all.
 */
internal fun reencodeJpeg(data: NSData, maxEdge: Int, quality: Int): CapturedJpeg? =
    UIImage.imageWithData(data)?.downscaledJpeg(maxEdge, quality)

/**
 * Draw into a bitmap no larger than [maxEdge] on its longest side and encode.
 *
 * Never upscales: a camera that hands back something smaller than the target is
 * giving us all the detail that exists, and stretching it would only inflate the
 * upload.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.downscaledJpeg(maxEdge: Int, quality: Int): CapturedJpeg? {
    val (width, height) = size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null

    val scale = (maxEdge.toDouble() / max(width, height)).coerceAtMost(1.0)
    val targetWidth = max(1.0, (width * scale).roundToInt().toDouble())
    val targetHeight = max(1.0, (height * scale).roundToInt().toDouble())

    // scale = 1.0 forces a 1:1 pixel context. The device default would multiply
    // by the screen scale and silently produce a 3x larger image than asked for.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), true, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val rendered = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    val jpeg = rendered?.let { UIImageJPEGRepresentation(it, quality / 100.0) } ?: return null
    return CapturedJpeg(
        bytes = jpeg.toByteArray(),
        width = targetWidth.toInt(),
        height = targetHeight.toInt(),
    )
}

/** Copy an `NSData` into a Kotlin [ByteArray]. */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return out
}

/**
 * One shared [CIContext] for the whole SDK.
 *
 * Constructing one per frame is the standard Core Image performance trap: each
 * instance compiles and caches its own GPU pipeline state, so a fresh context in
 * a per-frame path costs more than everything else in that path combined.
 */
@OptIn(ExperimentalForeignApi::class)
private val sharedCiContext: CIContext by lazy { CIContext() }
