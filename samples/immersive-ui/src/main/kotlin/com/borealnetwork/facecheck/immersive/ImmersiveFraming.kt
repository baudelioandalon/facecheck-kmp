package com.borealnetwork.facecheck.immersive

import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.PositioningHint
import kotlin.math.abs

/**
 * The preview gate must coach the user before the enrollment starts. The
 * liveness machine applies the same upper bound once it is running; keeping
 * this helper on the preview side makes the pre-start UI use that contract too.
 */
internal fun framingHintFor(
    frame: FaceFrame,
    @Suppress("UNUSED_PARAMETER")
    previewAspectRatio: Float,
): PositioningHint? = when {
    frame.faceCount <= 0 -> PositioningHint.NO_FACE
    frame.faceCount > 1 -> PositioningHint.MULTIPLE_FACES
    frame.faceRatio < IMMERSIVE_MIN_FACE_RATIO -> PositioningHint.MOVE_CLOSER
    frame.faceRatio > IMMERSIVE_MAX_FACE_RATIO -> PositioningHint.MOVE_AWAY
    !frame.insideGuide -> PositioningHint.OUTSIDE_GUIDE
    frame.quality.detectorScore < IMMERSIVE_MIN_DETECTOR_SCORE -> PositioningHint.NO_FACE
    frame.quality.brightness < IMMERSIVE_MIN_BRIGHTNESS -> PositioningHint.TOO_DARK
    frame.quality.brightness > IMMERSIVE_MAX_BRIGHTNESS -> PositioningHint.TOO_BRIGHT
    abs(frame.roll) > IMMERSIVE_MAX_ROLL_DEG -> PositioningHint.STRAIGHTEN_HEAD
    abs(frame.yaw) > IMMERSIVE_MAX_YAW_DEG ||
        abs(frame.pitch) > IMMERSIVE_MAX_PITCH_DEG -> PositioningHint.LOOK_STRAIGHT
    else -> null
}

/** Distance-only fallback used while the preview has not reported its size. */
internal fun framingHintFor(frame: FaceFrame): PositioningHint? = when {
    frame.faceCount == 1 && frame.faceRatio > IMMERSIVE_MAX_FACE_RATIO ->
        PositioningHint.MOVE_AWAY
    else -> null
}

internal const val IMMERSIVE_MIN_FACE_RATIO = 0.25f
internal const val IMMERSIVE_MAX_FACE_RATIO = 0.90f
internal const val IMMERSIVE_MIN_DETECTOR_SCORE = 0.85f
internal const val IMMERSIVE_MIN_BRIGHTNESS = 50f
internal const val IMMERSIVE_MAX_BRIGHTNESS = 220f
internal const val IMMERSIVE_MAX_YAW_DEG = 10f
internal const val IMMERSIVE_MAX_PITCH_DEG = 10f
internal const val IMMERSIVE_MAX_ROLL_DEG = 20f
