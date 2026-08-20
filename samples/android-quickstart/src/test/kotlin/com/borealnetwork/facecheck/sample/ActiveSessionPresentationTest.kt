package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActiveSessionPresentationTest {

    @Test
    fun `retry creates fresh attempt and returns to preflight`() {
        val failed = ActiveSessionUiState(
            operation = SampleOperation.ENROLL,
            attempt = EnrollmentAttempt.first,
            phase = ActiveSessionPhase.RecoverableError("No fue posible completar la sesión"),
            sessionId = "ls_old",
        )

        val next = ActiveSessionPresentation.reduce(failed, ActiveSessionAction.Retry)

        assertEquals("Intento 2 de 3", next.attempt.label)
        assertEquals(ActiveSessionPhase.Preflight, next.phase)
        assertNull(next.sessionId)
    }

    @Test
    fun `error never keeps processing headline`() {
        val state = ActiveSessionPresentation.reduce(
            ActiveSessionUiState(
                operation = SampleOperation.ENROLL,
                phase = ActiveSessionPhase.Processing,
            ),
            ActiveSessionAction.Failed(
                FaceCheckException(FaceCheckErrorCode.TIMEOUT, "No fue posible completar la sesión"),
            ),
        )

        assertEquals("Volvamos a intentarlo", state.title)
        assertFalse(state.visibleText.contains("Guardando enrolamiento"))
    }

    @Test
    fun `countdown uses server expiry`() {
        assertEquals(
            "1:15",
            ActiveSessionPresentation.countdownLabel(
                expiresAt = Instant.parse("2026-08-14T12:02:00Z"),
                now = Instant.parse("2026-08-14T12:00:45Z"),
            ),
        )
    }
}
