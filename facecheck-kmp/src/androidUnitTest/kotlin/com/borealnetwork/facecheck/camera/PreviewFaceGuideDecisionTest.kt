package com.borealnetwork.facecheck.camera

import android.graphics.RectF
import android.graphics.Matrix
import android.util.Size
import androidx.camera.view.transform.OutputTransform
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PreviewFaceGuideDecisionTest {

    @Test
    fun `CameraX transform maps cropped analysis bounds into a translated preview`() {
        val source = outputTransform(
            Matrix().apply {
                setScale(2f, 2f)
                postTranslate(-100f, -50f)
            },
        )
        val target = outputTransform(Matrix().apply { setTranslate(10f, 20f) })
        var received: RectF? = null

        val accepted = mapFaceBoundsToPreviewGuide(
            source = source,
            target = target,
            faceBounds = RectF(120f, 60f, 160f, 100f),
        ) { bounds ->
            received = RectF(bounds)
            true
        }

        assertTrue(accepted)
        assertEquals(RectF(120f, 75f, 140f, 95f), received)
    }

    @Test
    fun `missing source or target transform blocks without calling the guide`() {
        var calls = 0
        val target = outputTransform(Matrix())
        val source = outputTransform(Matrix())

        assertFalse(
            mapFaceBoundsToPreviewGuide(
                source = null,
                target = target,
                faceBounds = RectF(1f, 2f, 3f, 4f),
            ) { calls += 1; true },
        )
        assertFalse(
            mapFaceBoundsToPreviewGuide(
                source = source,
                target = null,
                faceBounds = RectF(1f, 2f, 3f, 4f),
            ) { calls += 1; true },
        )
        assertEquals(0, calls)
    }

    private fun outputTransform(matrix: Matrix): OutputTransform = OutputTransform(matrix, Size(200, 100))
}
