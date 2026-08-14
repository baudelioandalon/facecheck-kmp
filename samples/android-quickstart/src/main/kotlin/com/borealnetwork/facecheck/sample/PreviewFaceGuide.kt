package com.borealnetwork.facecheck.sample

import android.graphics.RectF

/** Callback the sample gives the camera controller after it maps a face into preview pixels. */
internal fun interface PreviewFaceGuide {
    fun contains(mappedFaceBounds: RectF): Boolean
}
