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
     * True when [mappedFaceBounds] is visually centered inside the oval.
     *
     * ML Kit reports a rectangular bounding box around an organic oval-ish
     * face. Requiring all four rectangle corners to fit inside the guide rejects
     * a real face that looks correctly centered to the user, especially near the
     * forehead and jaw. The center plus the four cardinal midpoints match the
     * visible guide better: the user still cannot leave the oval horizontally or
     * vertically, but natural detector corners no longer block progress.
     */
    fun contains(mappedFaceBounds: RectF): Boolean {
        val current = oval ?: return false
        val centerX = (mappedFaceBounds.left + mappedFaceBounds.right) / 2f
        val centerY = (mappedFaceBounds.top + mappedFaceBounds.bottom) / 2f
        return current.contains(centerX, centerY) &&
            current.contains(mappedFaceBounds.left, centerY) &&
            current.contains(mappedFaceBounds.right, centerY) &&
            current.contains(centerX, mappedFaceBounds.top) &&
            current.contains(centerX, mappedFaceBounds.bottom)
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
