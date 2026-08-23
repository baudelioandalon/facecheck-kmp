package com.borealnetwork.facecheck.sample

import android.graphics.RectF
import kotlin.math.min

/**
 * The INE guide rectangle in preview coordinates.
 *
 * The same instance decides whether the detected face fits inside the document
 * guide, so drawing and gating cannot drift apart.
 */
internal class DocumentGuideGeometry {

    @Volatile
    private var rectangle: RectF? = null

    fun updateForViewport(width: Int, height: Int) {
        rectangle = if (width <= 0 || height <= 0) {
            null
        } else {
            val frameWidth = min(width * 0.84f, height * 0.68f)
            val frameHeight = frameWidth / 1.585f
            val left = (width - frameWidth) / 2f
            val top = height * 0.22f
            RectF(left, top, left + frameWidth, min(height * 0.82f, top + frameHeight))
        }
    }

    fun rectangleBounds(): RectF? = rectangle?.let { RectF(it) }

    fun contains(mappedFaceBounds: RectF): Boolean {
        val current = rectangle ?: return false
        val centerX = (mappedFaceBounds.left + mappedFaceBounds.right) / 2f
        val centerY = (mappedFaceBounds.top + mappedFaceBounds.bottom) / 2f
        return current.contains(centerX, centerY) &&
            current.contains(mappedFaceBounds.left, centerY) &&
            current.contains(mappedFaceBounds.right, centerY) &&
            current.contains(centerX, mappedFaceBounds.top) &&
            current.contains(centerX, mappedFaceBounds.bottom)
    }
}
