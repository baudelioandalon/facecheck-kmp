package com.borealnetwork.facecheck.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the estimator both camera pipelines share.
 *
 * The one that matters is [subsamplingDoesNotChangeSharpness]. Android and iOS
 * used to compute sharpness separately, and the iOS copy read its Laplacian
 * neighbours a full sampling step away instead of one pixel away. That inflates
 * the variance by roughly the fourth power of the step, and since the step is
 * derived from the face box, the *same face at a different distance* scored
 * differently on the same phone. Nothing crashed and no test failed:
 * `LivenessConfig.minSharpness` simply stopped meaning anything on one of the
 * two platforms.
 */
class LumaSamplingTest {

    // --- Test images ----------------------------------------------------------

    /**
     * A smooth ripple whose curvature — and therefore its Laplacian — is the
     * same everywhere and at any region size. Measuring it densely and sparsely
     * must give the same answer; that is the whole point.
     */
    private fun ripple(x: Int, y: Int): Int =
        (128.0 + 120.0 * sin(x / 3.0) * sin(y / 3.0)).roundToInt().coerceIn(0, 255)

    /**
     * Uncorrelated pixel to pixel: as much fine detail as an image can hold.
     *
     * A checkerboard would be the obvious choice and is a trap — its period is
     * 2, so any even sampling step lands on one colour and reports a *perfectly
     * uniform* Laplacian. This is aliasing-proof.
     */
    private fun fineDetail(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        return (h xor (h shr 16)) and 0xFF
    }

    /** A gentle ramp: real content, but nothing a Laplacian should call sharp. */
    private fun ramp(x: Int, y: Int): Int = ((x + y) / 8) % 256

    private fun flat(level: Int): (Int, Int) -> Int = { _, _ -> level }

    // --- The regression tests -------------------------------------------------

    @Test
    fun subsamplingDoesNotChangeSharpness() {
        // 48 px across: one sample per pixel, stencil and grid coincide.
        val dense = assertNotNull(sampleLumaStats(1, 1, 49, 49, ::ripple))
        // 384 px across: the same 48 samples per axis, now eight pixels apart.
        val sparse = assertNotNull(sampleLumaStats(1, 1, 385, 385, ::ripple))

        assertEquals(dense.samples, sparse.samples, "both should take the same sample count")

        val ratio = sparse.sharpness / dense.sharpness
        assertTrue(
            ratio > 0.5f && ratio < 2f,
            "sharpness must not scale with the sampling step: dense=${dense.sharpness} " +
                "sparse=${sparse.sharpness} (ratio $ratio). A stencil widened to the step " +
                "puts this in the hundreds.",
        )
    }

    @Test
    fun neighboursAreReadOnePixelAway() {
        val asked = mutableSetOf<Pair<Int, Int>>()
        sampleLumaStats(100, 100, 484, 484) { x, y ->
            asked += x to y
            ripple(x, y)
        }

        // The first centre is (100, 100) and the step is 8. Reading (99, 100)
        // proves the stencil is one pixel wide; never reading (92, 100) proves
        // it is not a step wide.
        assertTrue(99 to 100 in asked, "the west neighbour of the first centre must be read")
        assertTrue(101 to 100 in asked, "the east neighbour of the first centre must be read")
        assertTrue(100 to 99 in asked, "the north neighbour of the first centre must be read")
        assertTrue(100 to 101 in asked, "the south neighbour of the first centre must be read")
        assertTrue(
            92 to 100 !in asked,
            "a neighbour a full step away was read; that measures a downscaled image",
        )
    }

    @Test
    fun sharpnessIsIndependentOfWhereTheFaceSitsInTheFrame() {
        val corner = assertNotNull(sampleLumaStats(1, 1, 193, 193, ::ripple))
        val elsewhere = assertNotNull(sampleLumaStats(601, 401, 793, 593, ::ripple))
        assertTrue(
            abs(corner.sharpness - elsewhere.sharpness) < corner.sharpness * 0.5f,
            "the same texture measured in two places gave ${corner.sharpness} and " +
                "${elsewhere.sharpness}",
        )
    }

    // --- Ordering the estimator has to preserve -------------------------------

    @Test
    fun sharpImagesOutscoreSmoothOnes() {
        val sharp = assertNotNull(sampleLumaStats(1, 1, 97, 97, ::fineDetail))
        val smooth = assertNotNull(sampleLumaStats(1, 1, 97, 97, ::ramp))
        val blank = assertNotNull(sampleLumaStats(1, 1, 97, 97, flat(128)))

        assertTrue(
            sharp.sharpness > smooth.sharpness,
            "fine detail ${sharp.sharpness} should beat a ramp ${smooth.sharpness}",
        )
        assertTrue(
            smooth.sharpness >= blank.sharpness,
            "a ramp ${smooth.sharpness} should not read softer than a flat field",
        )
        assertEquals(0f, blank.sharpness, 0.001f, "a flat field has no detail at all")
    }

    @Test
    fun theConfiguredBlurFloorSeparatesTheTwo() {
        // `LivenessConfig.minSharpness` is 25, and it is one number compared
        // against whatever either platform reports. A smear has to land below it
        // and a detailed face above it, or the LOW_QUALITY hint is decoration.
        val blurred = assertNotNull(
            sampleLumaStats(1, 1, 193, 193) { x, y ->
                // The same ripple stretched over forty pixels instead of three:
                // what a hand-shake smear leaves behind, where the Laplacian is
                // down in the quantisation noise.
                (128.0 + 100.0 * sin(x / 40.0) * sin(y / 40.0)).roundToInt()
            },
        )
        assertTrue(
            blurred.sharpness < 25f,
            "a near-flat gradient read as sharpness ${blurred.sharpness}",
        )

        val crisp = assertNotNull(sampleLumaStats(1, 1, 193, 193, ::fineDetail))
        assertTrue(
            crisp.sharpness > 25f,
            "a fully detailed image read as sharpness ${crisp.sharpness}",
        )
    }

    // --- Brightness -----------------------------------------------------------

    @Test
    fun brightnessIsTheMeanOfTheSampledPixels() {
        assertEquals(90f, assertNotNull(sampleLumaStats(0, 0, 64, 64, flat(90))).brightness, 0.001f)
    }

    @Test
    fun brightnessTracksTheRectangleNotTheWholePlane() {
        // Dark on the left, blown out on the right. Only the rectangle decides,
        // which is why both platforms pass a face crop rather than the frame:
        // a backlit user has a fine *frame* average and an unusable face.
        val split: (Int, Int) -> Int = { x, _ -> if (x < 100) 30 else 240 }
        assertEquals(30f, assertNotNull(sampleLumaStats(1, 1, 65, 65, split)).brightness, 0.001f)
        assertEquals(
            240f,
            assertNotNull(sampleLumaStats(101, 1, 165, 65, split)).brightness,
            0.001f,
        )
    }

    // --- Refusing to guess ----------------------------------------------------

    @Test
    fun returnsNullRatherThanMeasureTooFewPixels() {
        // Nine centres, below MIN_SAMPLES. A variance over that is noise, and
        // the callers report their neutral defaults instead of publishing it.
        assertNull(sampleLumaStats(1, 1, 4, 4, flat(120)))
    }

    @Test
    fun returnsNullWhenNoPixelIsReadable() {
        assertNull(sampleLumaStats(0, 0, 200, 200) { _, _ -> -1 })
    }

    @Test
    fun dropsSamplesWhoseNeighboursAreUnreadable() {
        // A plane that refuses everything on its top row: those centres have no
        // usable stencil, so they must not reach the brightness average either.
        val stats = assertNotNull(
            sampleLumaStats(0, 0, 96, 96) { x, y ->
                if (y <= 0) -1 else if (x < 48) 40 else 200
            },
        )
        assertEquals(120f, stats.brightness, 0.001f)
        assertTrue(stats.samples > MIN_SAMPLES, "should still have plenty of usable samples")
    }

    @Test
    fun rejectsEmptyOrInvertedRectangles() {
        assertNull(sampleLumaStats(10, 10, 10, 40, flat(120)))
        assertNull(sampleLumaStats(10, 10, 40, 10, flat(120)))
        assertNull(sampleLumaStats(40, 40, 10, 10, flat(120)))
    }
}