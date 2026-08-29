package com.borealnetwork.facecheck.immersive

import kotlin.math.max

/**
 * Tests the whole detected face box against the oval shown by the preview.
 *
 * Camera analysis and the preview can have different aspect ratios because
 * [androidx.camera.view.PreviewView.ScaleType.FILL_CENTER] crops the image.
 * Mapping the four box corners through that crop avoids accepting a face that
 * is only centered by its midpoint while part of it remains outside the oval.
 */
internal fun isFaceBoxInsideOval(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    imageAspectRatio: Float,
    previewAspectRatio: Float,
): Boolean {
    if (width <= 0f || height <= 0f || imageAspectRatio <= 0f || previewAspectRatio <= 0f) {
        return false
    }

    val fillScale = max(1f, previewAspectRatio / imageAspectRatio)
    val displayedImageWidth = imageAspectRatio * fillScale
    val displayedImageHeight = fillScale
    val cropOffsetX = (previewAspectRatio - displayedImageWidth) / 2f
    val cropOffsetY = (1f - displayedImageHeight) / 2f

    fun mapX(value: Float): Float = (cropOffsetX + value * displayedImageWidth) / previewAspectRatio
    fun mapY(value: Float): Float = cropOffsetY + value * displayedImageHeight

    // ML Kit's box includes detector padding around the visible facial area
    // (hair, ears and a little background). Validate the facial core instead
    // of its rectangular corners, otherwise a real face that visually fits
    // the oval is rejected by the ellipse's curved top/bottom edges.
    val coreInset = FACE_CORE_INSET_FRACTION
    val coreWidth = width * (1f - 2f * coreInset)
    val coreHeight = height * (1f - 2f * coreInset)
    val left = mapX(centerX - coreWidth / 2f)
    val right = mapX(centerX + coreWidth / 2f)
    val top = mapY(centerY - coreHeight / 2f)
    val bottom = mapY(centerY + coreHeight / 2f)

    val ovalCenterX = 0.5f
    val ovalCenterY = OVAL_CENTRE_FRACTION
    val ovalWidth = OVAL_WIDTH_FRACTION
    val ovalHeight = OVAL_WIDTH_FRACTION * OVAL_ASPECT * previewAspectRatio
    val radiusX = ovalWidth / 2f
    val radiusY = ovalHeight / 2f

    fun contains(x: Float, y: Float): Boolean {
        val normalizedX = (x - ovalCenterX) / radiusX
        val normalizedY = (y - ovalCenterY) / radiusY
        return normalizedX * normalizedX + normalizedY * normalizedY <= 1f
    }

    return contains(left, top) &&
        contains(right, top) &&
        contains(left, bottom) &&
        contains(right, bottom)
}

private const val FACE_CORE_INSET_FRACTION = 0.20f
