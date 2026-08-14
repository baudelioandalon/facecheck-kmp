package com.borealnetwork.facecheck.sample

import android.graphics.RectF
import kotlin.math.min

/**
 * The face-guide oval in the actual pixel coordinates of the camera preview.
 *
 * The overlay updates this object whenever its viewport changes. The same
 * instance then decides whether CameraX-mapped face bounds are fully contained
 * by the oval, so drawing and gating cannot drift apart.
 */
internal class FaceGuideGeometry {

    @Volatile
    private var oval: Oval? = null

    /** Update the oval for a preview viewport measured in pixels. */
    fun updateForViewport(width: Int, height: Int) {
        oval = if (width <= 0 || height <= 0) {
            null
        } else {
            val frameWidth = min(width * 0.76f, height * 0.54f)
            val frameHeight = frameWidth * 1.28f
            val left = (width - frameWidth) / 2f
            val top = height * 0.16f
            Oval(
                left = left,
                top = top,
                right = left + frameWidth,
                bottom = min(height * 0.78f, top + frameHeight),
            )
        }
    }

    /** A defensive copy of the oval to draw, or null until the viewport is sized. */
    fun ovalBounds(): RectF? = oval?.let { RectF(it.left, it.top, it.right, it.bottom) }

    /**
     * True only when every corner of [mappedFaceBounds] is strictly inside the oval.
     *
     * Strict containment deliberately rejects a face that touches the visible
     * border: the person must leave a little margin rather than appear clipped.
     */
    fun contains(mappedFaceBounds: RectF): Boolean {
        val current = oval ?: return false
        return current.contains(mappedFaceBounds.left, mappedFaceBounds.top) &&
            current.contains(mappedFaceBounds.right, mappedFaceBounds.top) &&
            current.contains(mappedFaceBounds.left, mappedFaceBounds.bottom) &&
            current.contains(mappedFaceBounds.right, mappedFaceBounds.bottom)
    }

    private data class Oval(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        private val centerX: Float = (left + right) / 2f
        private val centerY: Float = (top + bottom) / 2f
        private val radiusX: Float = (right - left) / 2f
        private val radiusY: Float = (bottom - top) / 2f

        fun contains(x: Float, y: Float): Boolean {
            if (radiusX <= 0f || radiusY <= 0f) return false
            val horizontal = (x - centerX) / radiusX
            val vertical = (y - centerY) / radiusY
            return horizontal * horizontal + vertical * vertical < 1f
        }
    }
}
