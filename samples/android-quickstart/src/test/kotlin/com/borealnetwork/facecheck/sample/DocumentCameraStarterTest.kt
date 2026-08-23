package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentCameraStarterTest {

    @Test
    fun startDocumentCamera_invokesStarterOnce() {
        var calls = 0

        startDocumentCamera(
            DocumentCameraStarter {
                calls += 1
            },
        )

        assertEquals(1, calls)
    }
}
