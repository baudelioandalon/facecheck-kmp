package com.borealnetwork.facecheck.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.borealnetwork.facecheck.liveness.FrameQuality

/**
 * Brightness and sharpness measured on the luminance plane, over the face only.
 *
 * ML Kit reports no image quality at all — it either finds a face or it does
 * not — so the numbers
 * [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine] coaches on
 * have to be computed here.
 *
 * Measured over the face box rather than the whole frame because that is what
 * the thresholds mean and what the backend reports back: a user standing in
 * front of a window has a bright *frame* and an unusably dark *face*, and a
 * whole-frame average would tell them everything is fine right up until the
 * match fails.
 *
 * Units match `FaceQuality` in `functions-python/facecheck/engine.py` so the
 * device-side hint and the server-side hint agree with each other.
 *
 * The arithmetic itself is [sampleLumaStats] in `commonMain`, shared with iOS;
 * this object only locates the pixels. See that function's KDoc for why the two
 * platforms are not allowed their own estimator.
 */
internal object LumaMetrics {

    /**
     * ML Kit exposes no per-detection confidence, so a returned face is taken at
     * face value: it already passed the detector's own internal threshold, and
     * inventing a number below `LivenessConfig.minDetectorScore` here would
     * stall positioning forever.
     */
    private const val ASSUMED_DETECTOR_SCORE = 1f

    /**
     * Sample the Y plane inside [faceInBuffer] and score it.
     *
     * @param faceInBuffer the face box already mapped into buffer coordinates by
     *   [FrameGeometry.uprightRectToBuffer]. Anything outside the buffer is
     *   clipped away.
     */
    fun measure(proxy: ImageProxy, faceInBuffer: Rect): FrameQuality {
        // One pixel of inset because the Laplacian reads all four neighbours.
        val box = Rect(faceInBuffer).apply {
            if (!intersect(1, 1, proxy.width - 1, proxy.height - 1)) setEmpty()
        }
        if (box.isEmpty) return neutral()

        val plane = proxy.planes.getOrNull(0) ?: return neutral()
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // Absolute gets only: the plane's position/limit belong to CameraX and
        // moving them would corrupt whatever reads the buffer next.
        val capacity = buffer.capacity()

        fun lumaAt(x: Int, y: Int): Int {
            val index = y * rowStride + x * pixelStride
            return if (index in 0 until capacity) buffer.get(index).toInt() and 0xFF else -1
        }

        val stats = sampleLumaStats(
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            luma = ::lumaAt,
        ) ?: return neutral()

        return FrameQuality(
            sharpness = stats.sharpness,
            brightness = stats.brightness,
            detectorScore = ASSUMED_DETECTOR_SCORE,
        )
    }

    /**
     * What to report when the plane could not be read.
     *
     * Deliberately the *passing* values rather than zeros: an unreadable buffer
     * is a bug in this file, and failing the user's session over it would turn
     * a diagnostic problem into a support ticket about "the app says there is
     * not enough light".
     */
    private fun neutral(): FrameQuality = FrameQuality(
        sharpness = FrameQuality.DEFAULT_SHARPNESS,
        brightness = FrameQuality.DEFAULT_BRIGHTNESS,
        detectorScore = ASSUMED_DETECTOR_SCORE,
    )
}