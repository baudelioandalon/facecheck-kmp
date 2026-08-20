package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureLoadingPresentationTest {

    @Test
    fun `enrollment preparation explains session setup before capture starts`() {
        val presentation = CaptureLoadingPresentation.preparing(SampleOperation.ENROLL)

        assertEquals("Preparando sesión segura…", presentation.title)
        assertEquals(
            "Confirmamos permisos, ubicación y conexión antes de capturar.",
            presentation.body,
        )
    }

    @Test
    fun `enrollment finalizing explains save only after liveness steps complete`() {
        val presentation = CaptureLoadingPresentation.finalizing(SampleOperation.ENROLL)

        assertEquals("Guardando enrolamiento…", presentation.title)
        assertEquals(
            "Tus tres pasos se completaron. Protegemos el registro antes de continuar.",
            presentation.body,
        )
    }
}
