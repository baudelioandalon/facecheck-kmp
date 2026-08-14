package com.borealnetwork.facecheck.camera

import android.graphics.Rect
import com.borealnetwork.facecheck.liveness.NormalizedFaceBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameGeometryTest {

    @Test
    fun `normalizes an upright ML Kit rectangle`() {
        assertEquals(
            NormalizedFaceBounds(.25f, .20f, .75f, .80f),
            FrameGeometry.normalizedBounds(
                rect = rect(left = 100, top = 80, right = 300, bottom = 320),
                uprightWidth = 400,
                uprightHeight = 400,
            ),
        )
    }

    @Test
    fun `clamps a rectangle that extends beyond the upright image`() {
        assertEquals(
            NormalizedFaceBounds(0f, 0f, 1f, 1f),
            FrameGeometry.normalizedBounds(
                rect = rect(left = -10, top = -20, right = 410, bottom = 420),
                uprightWidth = 400,
                uprightHeight = 400,
            ),
        )
    }

    @Test
    fun `requires positive upright dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            FrameGeometry.normalizedBounds(
                rect = rect(left = 0, top = 0, right = 100, bottom = 100),
                uprightWidth = 0,
                uprightHeight = 400,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FrameGeometry.normalizedBounds(
                rect = rect(left = 0, top = 0, right = 100, bottom = 100),
                uprightWidth = 400,
                uprightHeight = 0,
            )
        }
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
