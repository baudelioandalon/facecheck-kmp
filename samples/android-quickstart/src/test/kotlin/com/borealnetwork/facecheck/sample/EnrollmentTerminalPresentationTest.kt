package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EnrollmentTerminalPresentationTest {

    @Test
    fun `failure maps a known wire code to a canonical safe message`() {
        val failure = EnrollmentTerminalPresentation.failure(
            attempt = EnrollmentAttempt.first,
            code = "INVALID_IMAGE",
            message = "Detalle técnico no apto para UI.",
        )

        assertFalse(failure.showsLoading)
        assertEquals("INVALID_IMAGE", failure.code)
        assertEquals("No se pudo leer la imagen. Vuelve a tomar la foto.", failure.message)
        assertEquals("Intento 1 de 3", failure.attempt.label)
    }

    @Test
    fun `unknown wire code uses stable unknown code and Mexican Spanish fallback`() {
        val failure = EnrollmentTerminalPresentation.failure(
            attempt = EnrollmentAttempt.first,
            code = "UNRECOGNIZED_REMOTE_CODE",
            message = "Detalle técnico no apto para UI.",
        )

        assertEquals("UNKNOWN", failure.code)
        assertEquals("No pudimos completar el enrolamiento. Intenta de nuevo.", failure.message)
    }

    @Test
    fun `non retryable failure still allows a fresh capture while attempts remain`() {
        val failure = EnrollmentTerminalPresentation.failure(
            attempt = EnrollmentAttempt.first,
            code = "INVALID_IMAGE",
            message = "Detalle técnico no apto para UI.",
        )

        assertFalse(failure.retryable)
        assertEquals("Intento 2 de 3", failure.nextAttempt?.label)
    }

    @Test
    fun `recapture eligibility progresses from attempt one through three then stops`() {
        val first = EnrollmentTerminalPresentation.failure(
            attempt = EnrollmentAttempt.first,
            code = "INVALID_IMAGE",
            message = "Detalle técnico no apto para UI.",
        )
        val second = EnrollmentTerminalPresentation.failure(
            attempt = checkNotNull(first.nextAttempt),
            code = "INVALID_IMAGE",
            message = "Detalle técnico no apto para UI.",
        )
        val third = EnrollmentTerminalPresentation.failure(
            attempt = checkNotNull(second.nextAttempt),
            code = "INVALID_IMAGE",
            message = "Detalle técnico no apto para UI.",
        )

        assertEquals("Intento 1 de 3", first.attempt.label)
        assertEquals("Intento 2 de 3", second.attempt.label)
        assertEquals("Intento 3 de 3", third.attempt.label)
        assertNull(third.nextAttempt)
    }

    @Test
    fun `exception failure keeps only canonical safe metadata`() {
        val failure = EnrollmentTerminalPresentation.failure(
            attempt = EnrollmentAttempt.first,
            error = FaceCheckException(
                code = FaceCheckErrorCode.INVALID_IMAGE,
                message = "Respuesta remota que no debe llegar a la UI",
            ),
        )

        assertEquals("INVALID_IMAGE", failure.code)
        assertEquals("No se pudo leer la imagen. Vuelve a tomar la foto.", failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun `failure diagnostic retains safe terminal presentation and HTTP status`() {
        val exception = FaceCheckException(
            code = FaceCheckErrorCode.INTERNAL,
            message = "Detalle del backend no apto para la interfaz.",
            httpStatus = 503,
            details = mapOf("diagnostic" to "backend-only"),
        )

        val diagnostic = EnrollmentFailureDiagnostic.from(
            attempt = EnrollmentAttempt.first,
            error = exception,
        )

        assertFalse(diagnostic.presentation.showsLoading)
        assertEquals("INTERNAL", diagnostic.presentation.code)
        assertEquals(503, diagnostic.httpStatus)
        assertEquals(true, diagnostic.presentation.retryable)
        assertEquals("Ocurrió un error inesperado. Intenta de nuevo.", diagnostic.presentation.message)
        assertNotEquals(exception.message, diagnostic.presentation.message)
    }

    @Test
    fun `safe error presentation drops raw exception text while retaining diagnostic metadata`() {
        val exception = FaceCheckException(
            code = FaceCheckErrorCode.INTERNAL,
            message = "Texto crudo del backend que no pertenece a la interfaz.",
            httpStatus = 503,
            details = mapOf("diagnostic" to "backend-only"),
        )

        val safe = FaceCheckFailurePresentation.from(exception)

        assertEquals("INTERNAL", safe.code)
        assertEquals("Ocurrió un error inesperado. Intenta de nuevo.", safe.message)
        assertEquals(503, safe.httpStatus)
        assertEquals(true, safe.retryable)
        assertNotEquals(exception.message, safe.message)
    }

    @Test
    fun `completion does not show loading`() {
        val completion = EnrollmentTerminalPresentation.completion(EnrollmentAttempt.first)

        assertFalse(completion.showsLoading)
        assertEquals("ENROLLMENT_COMPLETE", completion.code)
    }
}
