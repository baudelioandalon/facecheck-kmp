package com.borealnetwork.facecheck.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferIsPlanar
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Vision.VNFaceObservation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mean luminance and Laplacian variance over the face crop.
 *
 * These are the same two numbers the backend reports in
 * [FaceQuality][com.borealnetwork.facecheck.model.FaceQuality], computed the same way, so
 * a frame the SDK judged sharp and a frame the backend judged sharp mean the
 * same thing. They are measured over the *face*, not the whole frame: a
 * backlit user in a bright room has excellent frame brightness and an unusable
 * face, and telling them the light is fine is worse than saying nothing.
 */
internal data class PixelStats(val brightness: Float, val sharpness: Float)

/**
 * The image's size once [orientation] has been applied.
 *
 * Vision reports every coordinate in this space, not in the buffer's, so every
 * conversion back to pixels has to start here.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun uprightSize(buffer: CVPixelBufferRef, orientation: FrameOrientation): Size {
    val w = CVPixelBufferGetWidth(buffer).toDouble()
    val h = CVPixelBufferGetHeight(buffer).toDouble()
    return if (orientation.swapsAxes) Size(h, w) else Size(w, h)
}

/**
 * Luma statistics for the face box, or null when the buffer cannot be read.
 *
 * Works on the luma plane of a bi-planar YUV buffer when there is one — that is
 * what [IosCameraController] configures, and reading plane 0 is both free and
 * exactly the quantity wanted — and falls back to deriving luma from BGRA for a
 * host that feeds something else.
 *
 * This function only locates and reads pixels; the arithmetic is
 * [sampleLumaStats], shared with Android so the two platforms cannot drift
 * apart on what "sharpness 25" means. Read its KDoc before changing anything
 * here about how the crop is walked.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pixelStats(
    buffer: CVPixelBufferRef,
    face: VNFaceObservation,
    orientation: FrameOrientation,
): PixelStats? {
    val width = CVPixelBufferGetWidth(buffer).toInt()
    val height = CVPixelBufferGetHeight(buffer).toInt()
    if (width <= 0 || height <= 0) return null

    val crop = face.boundingBox.useContents {
        bufferCrop(
            visionX = origin.x,
            visionY = origin.y,
            visionW = size.width,
            visionH = size.height,
            orientation = orientation,
            width = width,
            height = height,
        )
    } ?: return null

    CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    try {
        val planar = CVPixelBufferIsPlanar(buffer)
        val base = if (planar) {
            CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)
        } else {
            CVPixelBufferGetBaseAddress(buffer)
        } ?: return null
        val stride = if (planar) {
            CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
        } else {
            CVPixelBufferGetBytesPerRow(buffer).toInt()
        }
        if (stride <= 0) return null

        val bytes = base.reinterpret<UByteVar>()
        // Plane 0 of a bi-planar YUV buffer *is* luma, one byte per pixel. For
        // BGRA there is no luma plane, so it is approximated from the green
        // channel: green carries ~72% of perceived luminance, and the error is
        // far below what a brightness threshold cares about.
        val pixelStride = if (planar) 1 else 4
        val channelOffset = if (planar) 0 else 1

        // Negative for anything off the plane: sampleLumaStats reads one pixel
        // past the rectangle on every side and drops the samples it cannot
        // complete, so this must answer for out-of-range coordinates rather
        // than walking off the buffer.
        fun luma(x: Int, y: Int): Int =
            if (x < 0 || y < 0 || x >= width || y >= height) {
                -1
            } else {
                bytes[(y.toLong() * stride + x.toLong() * pixelStride + channelOffset)].toInt()
            }

        // One pixel of inset so the stencil stays inside the buffer, matching
        // what LumaMetrics does on Android.
        val stats = sampleLumaStats(
            left = max(1, crop.left),
            top = max(1, crop.top),
            right = min(width - 1, crop.right),
            bottom = min(height - 1, crop.bottom),
            luma = ::luma,
        ) ?: return null

        return PixelStats(brightness = stats.brightness, sharpness = stats.sharpness)
    } finally {
        CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    }
}

/** A pixel rectangle in the buffer's own (unrotated, top-left origin) space. */
internal data class BufferCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Map Vision's normalised face box back onto buffer pixels.
 *
 * Two coordinate changes, both easy to get silently wrong:
 *
 *  1. Vision's origin is **bottom-left** and its rect is normalised to the
 *     *upright* image, not to the buffer.
 *  2. The buffer itself is not upright — [orientation] says how far it is
 *     turned — so the rect has to be rotated back by the same amount.
 *
 * Getting either wrong yields a crop of the wrong corner of the image, which
 * produces perfectly plausible brightness and sharpness numbers for a piece of
 * wall. That failure never crashes and never looks like a bug; it just makes
 * the coaching hints wrong for everybody.
 */
internal fun bufferCrop(
    visionX: Double,
    visionY: Double,
    visionW: Double,
    visionH: Double,
    orientation: FrameOrientation,
    width: Int,
    height: Int,
): BufferCrop? {
    if (visionW <= 0.0 || visionH <= 0.0) return null

    // Upright space, flipped to a top-left origin.
    val u0 = visionX
    val u1 = visionX + visionW
    val v0 = 1.0 - (visionY + visionH)
    val v1 = 1.0 - visionY

    // Un-rotate into buffer space. Each branch is the inverse of the rotation
    // that would make the buffer upright.
    val (bu0, bu1, bv0, bv1) = when (orientation) {
        FrameOrientation.UP -> Quad(u0, u1, v0, v1)
        FrameOrientation.RIGHT -> Quad(v0, v1, 1.0 - u1, 1.0 - u0)
        FrameOrientation.LEFT -> Quad(1.0 - v1, 1.0 - v0, u0, u1)
        FrameOrientation.DOWN -> Quad(1.0 - u1, 1.0 - u0, 1.0 - v1, 1.0 - v0)
    }

    val left = (bu0 * width).roundToInt().coerceIn(0, width - 1)
    val right = (bu1 * width).roundToInt().coerceIn(left + 1, width)
    val top = (bv0 * height).roundToInt().coerceIn(0, height - 1)
    val bottom = (bv1 * height).roundToInt().coerceIn(top + 1, height)
    return BufferCrop(left = left, top = top, right = right, bottom = bottom)
}

/** The four edges of an un-rotated rect, destructured at the call site. */
private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)