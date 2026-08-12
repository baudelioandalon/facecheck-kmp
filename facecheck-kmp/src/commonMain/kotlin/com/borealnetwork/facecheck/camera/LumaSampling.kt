package com.borealnetwork.facecheck.camera

import kotlin.math.max

/**
 * Mean luminance and Laplacian variance over a rectangle of a luma plane.
 *
 * `null` from [sampleLumaStats] means "not enough readable pixels to say
 * anything"; the caller reports its neutral defaults rather than a number it
 * made up.
 */
internal data class LumaStats(
    /** Mean luminance of the sampled pixels, 0..255. */
    val brightness: Float,
    /** Variance of the Laplacian over the sampled pixels. Higher is sharper. */
    val sharpness: Float,
    /** How many pixels contributed. Exposed for tests and logging. */
    val samples: Int,
)

/**
 * The one sharpness/brightness estimator, shared by both platforms.
 *
 * It lives in `commonMain` for a reason that is not tidiness.
 * [LivenessConfig.minSharpness][com.borealnetwork.facecheck.liveness.LivenessConfig.minSharpness]
 * and [FrameQuality.SHARPNESS_REFERENCE][com.borealnetwork.facecheck.liveness.FrameQuality.SHARPNESS_REFERENCE]
 * are single numbers compared against whatever the platform reports, and they
 * feed both the `LOW_QUALITY` coaching hint and 30% of the score that picks the
 * frame the SDK uploads. Two implementations of "variance of the Laplacian"
 * that disagree by a constant factor do not fail a build and do not fail a
 * test: they just mean the blur gate fires on Android and never on iOS, on the
 * same phone in the same room. So there is one implementation, and each
 * platform supplies nothing but a way to read a pixel.
 *
 * ### Why the kernel is always at distance 1
 *
 * The grid is subsampled — [SAMPLES_PER_AXIS]² points at most, so cost does not
 * grow with resolution — but the four neighbours are always the *adjacent*
 * pixels, never the next sample. Reading neighbours a full step away measures a
 * downscaled image, whose Laplacian variance grows roughly with the square of
 * the step, and reports every frame as far sharper than it is. Worse, the step
 * is derived from the face box, so the inflation would vary with how close the
 * user is standing: the same face at two distances would score two different
 * sharpnesses on the same device. Subsample the *centres*, never the stencil.
 *
 * @param luma reads one pixel, returning a value in 0..255, or **any negative
 *   number** for a coordinate it cannot read. Called for the centre and for its
 *   four neighbours, so it must tolerate coordinates one pixel outside
 *   [left]/[top]/[right]/[bottom].
 * @return null when fewer than [MIN_SAMPLES] pixels were readable.
 */
internal fun sampleLumaStats(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    luma: (x: Int, y: Int) -> Int,
): LumaStats? {
    if (right <= left || bottom <= top) return null

    val stepX = max(1, (right - left) / SAMPLES_PER_AXIS)
    val stepY = max(1, (bottom - top) / SAMPLES_PER_AXIS)

    var samples = 0
    var lumaSum = 0.0
    var laplacianSum = 0.0
    var laplacianSquareSum = 0.0

    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val centre = luma(x, y)
            if (centre >= 0) {
                val west = luma(x - 1, y)
                val east = luma(x + 1, y)
                val north = luma(x, y - 1)
                val south = luma(x, y + 1)
                if (west >= 0 && east >= 0 && north >= 0 && south >= 0) {
                    val laplacian = (4 * centre - west - east - north - south).toDouble()
                    laplacianSum += laplacian
                    laplacianSquareSum += laplacian * laplacian
                    lumaSum += centre
                    samples++
                }
            }
            x += stepX
        }
        y += stepY
    }

    if (samples < MIN_SAMPLES) return null

    val mean = laplacianSum / samples
    val variance = (laplacianSquareSum / samples) - (mean * mean)
    return LumaStats(
        brightness = (lumaSum / samples).toFloat(),
        sharpness = variance.coerceAtLeast(0.0).toFloat(),
        samples = samples,
    )
}

/** Grid resolution per axis. ~2.3k samples is under a millisecond and plenty. */
internal const val SAMPLES_PER_AXIS = 48

/** Below this many usable samples the numbers are noise; report neutral. */
internal const val MIN_SAMPLES = 16

/**
 * Head width as a fraction of frame width, below which a detection is dropped.
 *
 * Shared because the two detectors apply it in different places — ML Kit takes
 * it as `FaceDetectorOptions.setMinFaceSize`, Vision has no such option and
 * [VisionFaceDetector][com.borealnetwork.facecheck.camera] filters its observations by
 * hand — and the value has to be the same on both. It is not cosmetic:
 * [FaceFrame.faceCount][com.borealnetwork.facecheck.liveness.FaceFrame.faceCount] is
 * counted *after* this filter, and
 * [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine] fails the
 * session outright with `MULTIPLE_FACES` the first time it sees more than one.
 * A platform that skips the filter kills sessions over a face in a poster on
 * the far wall while the other platform never notices it.
 *
 * Just under `LivenessConfig.minFaceRatio` (0.25), which leaves a narrow band
 * where the SDK can see a face that is too small and say "acércate" instead of
 * "no face".
 *
 * It is not lower than that, and the reason is measured rather than
 * theoretical. At 0.15 the detector reports transient false faces on ordinary
 * room clutter — a whiteboard and a ceiling fan produced a steady stream of
 * 6%-wide "faces" on the test device, each one with a *new* tracking id because
 * none survived to the next frame. That is fatal rather than merely noisy:
 * `ChallengeMachine` binds the session to the first tracking id it is given and
 * fails with `FACE_SWAPPED` the moment a different one arrives, so the session
 * died in seconds with nobody in front of the camera. Detections the session
 * could never accept anyway are better filtered here than scored downstream.
 */
internal const val MIN_FACE_WIDTH_RATIO = 0.2f