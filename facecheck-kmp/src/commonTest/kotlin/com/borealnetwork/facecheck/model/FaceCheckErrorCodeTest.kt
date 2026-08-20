package com.borealnetwork.facecheck.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceCheckErrorCodeTest {

    @Test
    fun `liveness session invalid is a typed backend error`() {
        assertEquals(
            FaceCheckErrorCode.LIVENESS_SESSION_INVALID,
            FaceCheckErrorCode.fromWire("LIVENESS_SESSION_INVALID"),
        )
    }
}
