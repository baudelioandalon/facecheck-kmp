package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureFailurePresentationTest {

    @Test
    fun `unexpected capture start errors become a safe user message`() {
        val message = CaptureFailurePresentation.fromUnexpected(
            IllegalStateException("provider returned null"),
        )

        assertEquals(
            "No pudimos iniciar la sesión. Revisa permisos, luz y conexión; después intenta de nuevo.",
            message,
        )
    }
}
