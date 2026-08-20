package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlin.math.ceil

/** Local, non-biometric gate that lets the user center their face before starting a session. */
internal class CameraPreflightReadiness(
    private val requiredHoldMs: Long = STABLE_HOLD_MS,
) {
    private var stableSinceMs: Long? = null

    fun onFrame(frame: FaceFrame): State {
        val blockingInstruction = frame.blockingInstruction()
        if (blockingInstruction != null) {
            stableSinceMs = null
            return State(
                instruction = blockingInstruction,
                progress = 0f,
                isReady = false,
                remainingSeconds = ceil(requiredHoldMs / 1_000.0).toInt(),
            )
        }

        val startedAt = stableSinceMs ?: frame.timestampMs.also { stableSinceMs = it }
        val elapsed = (frame.timestampMs - startedAt).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / requiredHoldMs).coerceIn(0f, 1f)
        val remainingMs = (requiredHoldMs - elapsed).coerceAtLeast(0L)
        val isReady = elapsed >= requiredHoldMs
        return State(
            instruction = if (isReady) {
                "Rostro listo. Presiona Empezar cuando estés preparado"
            } else {
                "Mantén tu rostro dentro del óvalo ${ceil(remainingMs / 1_000.0).toInt()}…"
            },
            progress = progress,
            isReady = isReady,
            remainingSeconds = ceil(remainingMs / 1_000.0).toInt(),
        )
    }

    data class State(
        val instruction: String,
        val progress: Float,
        val isReady: Boolean,
        val remainingSeconds: Int,
    ) {
        val stepLabel: String = if (isReady) "Listo para empezar" else "Alineando rostro"
    }

    private fun FaceFrame.isFrontalEnough(): Boolean =
        kotlin.math.abs(yaw) <= MAX_FRONTAL_YAW && kotlin.math.abs(pitch) <= MAX_FRONTAL_PITCH

    private fun FaceFrame.blockingInstruction(): String? = when {
        faceCount == 0 -> "Coloca tu rostro dentro del óvalo"
        faceCount > 1 -> "Solo debe aparecer una persona"
        !insideGuide -> "Centra tu rostro completo dentro del óvalo"
        !isFrontalEnough() -> "Mira al frente sin inclinar la cabeza"
        else -> null
    }

    private companion object {
        const val STABLE_HOLD_MS = 3_000L
        const val MAX_FRONTAL_YAW = 12f
        const val MAX_FRONTAL_PITCH = 12f
    }
}

@Deprecated("Use CameraPreflightReadiness for both enrollment and verification.")
internal typealias VerificationPreflightReadiness = CameraPreflightReadiness
