package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame

/** Local, non-biometric gate that prevents a verification session from starting too early. */
internal class VerificationPreflightReadiness(
    private val stableHoldMs: Long = STABLE_HOLD_MS,
) {
    private var stableSinceMs: Long? = null

    fun onFrame(frame: FaceFrame): State {
        if (!frame.hasSingleFace || !frame.insideGuide) {
            stableSinceMs = null
            return State(
                instruction = "Coloca tu rostro completo dentro del óvalo",
                progress = 0f,
                isReady = false,
            )
        }

        val startedAt = stableSinceMs ?: frame.timestampMs.also { stableSinceMs = it }
        val elapsed = (frame.timestampMs - startedAt).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / stableHoldMs).coerceIn(0f, 1f)
        val isReady = elapsed >= stableHoldMs
        return State(
            instruction = if (isReady) {
                "Rostro listo. Presiona Empezar cuando estés preparado"
            } else {
                "Mantén tu rostro dentro del óvalo"
            },
            progress = progress,
            isReady = isReady,
        )
    }

    data class State(
        val instruction: String,
        val progress: Float,
        val isReady: Boolean,
    ) {
        val stepLabel: String = if (isReady) "Listo para empezar" else "Alineando rostro"
    }

    private companion object {
        const val STABLE_HOLD_MS = 1_000L
    }
}
