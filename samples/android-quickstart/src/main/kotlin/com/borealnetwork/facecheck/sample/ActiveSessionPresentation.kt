package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.datetime.Instant

internal data class ActiveSessionUiState(
    val operation: SampleOperation,
    val attempt: EnrollmentAttempt = EnrollmentAttempt.first,
    val phase: ActiveSessionPhase = ActiveSessionPhase.Preflight,
    val sessionId: String? = null,
) {
    val title: String
        get() = when (phase) {
            ActiveSessionPhase.Preflight -> "Prepárate"
            ActiveSessionPhase.CreatingSession -> "Creando sesión segura"
            ActiveSessionPhase.Capturing -> "Sigue las instrucciones"
            ActiveSessionPhase.Uploading -> if (operation == SampleOperation.ENROLL) {
                "Guardando enrolamiento"
            } else {
                "Verificando identidad"
            }
            ActiveSessionPhase.Processing -> "Procesando"
            ActiveSessionPhase.SuccessDialog -> if (operation == SampleOperation.ENROLL) {
                "Enrolamiento completado"
            } else {
                "Identidad verificada"
            }
            is ActiveSessionPhase.RecoverableError -> "Volvamos a intentarlo"
            is ActiveSessionPhase.TerminalError -> "No fue posible completar la sesión"
        }

    val visibleText: String
        get() = when (val current = phase) {
            ActiveSessionPhase.Preflight -> "Centra tu rostro y presiona Empezar."
            ActiveSessionPhase.CreatingSession -> "Estamos preparando un intento nuevo."
            ActiveSessionPhase.Capturing -> attempt.label
            ActiveSessionPhase.Uploading -> title
            ActiveSessionPhase.Processing -> "Procesando evidencia…"
            ActiveSessionPhase.SuccessDialog -> title
            is ActiveSessionPhase.RecoverableError -> current.message
            is ActiveSessionPhase.TerminalError -> current.message
        }
}

internal sealed interface ActiveSessionPhase {
    data object Preflight : ActiveSessionPhase
    data object CreatingSession : ActiveSessionPhase
    data object Capturing : ActiveSessionPhase
    data object Uploading : ActiveSessionPhase
    data object Processing : ActiveSessionPhase
    data object SuccessDialog : ActiveSessionPhase
    data class RecoverableError(val message: String) : ActiveSessionPhase
    data class TerminalError(val message: String) : ActiveSessionPhase
}

internal sealed interface ActiveSessionAction {
    data class SessionCreated(val sessionId: String) : ActiveSessionAction
    data object CaptureStarted : ActiveSessionAction
    data object Uploading : ActiveSessionAction
    data object Processing : ActiveSessionAction
    data object Succeeded : ActiveSessionAction
    data class Failed(val error: FaceCheckException) : ActiveSessionAction
    data object Retry : ActiveSessionAction
}

internal object ActiveSessionPresentation {
    fun reduce(
        state: ActiveSessionUiState,
        action: ActiveSessionAction,
    ): ActiveSessionUiState = when (action) {
        is ActiveSessionAction.SessionCreated ->
            state.copy(phase = ActiveSessionPhase.Capturing, sessionId = action.sessionId)
        ActiveSessionAction.CaptureStarted ->
            state.copy(phase = ActiveSessionPhase.Capturing)
        ActiveSessionAction.Uploading ->
            state.copy(phase = ActiveSessionPhase.Uploading)
        ActiveSessionAction.Processing ->
            state.copy(phase = ActiveSessionPhase.Processing)
        ActiveSessionAction.Succeeded ->
            state.copy(phase = ActiveSessionPhase.SuccessDialog)
        is ActiveSessionAction.Failed -> {
            val message = action.error.message.ifBlank { action.error.code.messageEs }
            if (state.attempt.retry() == null || !action.error.isRetryable) {
                state.copy(phase = ActiveSessionPhase.TerminalError(message))
            } else {
                state.copy(phase = ActiveSessionPhase.RecoverableError(message))
            }
        }
        ActiveSessionAction.Retry -> {
            val retry = state.attempt.retry() ?: state.attempt
            state.copy(
                attempt = retry,
                phase = ActiveSessionPhase.Preflight,
                sessionId = null,
            )
        }
    }

    fun countdownLabel(expiresAt: Instant, now: Instant): String {
        val remainingSeconds = ((expiresAt.toEpochMilliseconds() - now.toEpochMilliseconds()) / 1_000)
            .coerceAtLeast(0L)
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
