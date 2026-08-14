package com.borealnetwork.facecheck.liveness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceFrameBinaryCompatibilityTest {

    @Test
    fun `historical JVM constructor preserves new field defaults`() {
        val constructor = FaceFrame::class.java.getConstructor(
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType,
            Float::class.javaObjectType,
            Float::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Long::class.javaPrimitiveType,
            FrameQuality::class.java,
            Int::class.javaPrimitiveType,
        )

        val frame = constructor.newInstance(
            1f,
            2f,
            3f,
            .9f,
            .8f,
            .4f,
            7,
            42L,
            FrameQuality(sharpness = 111f, brightness = 122f, detectorScore = .8f),
            1,
        ) as FaceFrame

        assertEquals(7, frame.trackingId)
        assertNull(frame.bounds)
        assertTrue(frame.insideGuide)
    }
}
