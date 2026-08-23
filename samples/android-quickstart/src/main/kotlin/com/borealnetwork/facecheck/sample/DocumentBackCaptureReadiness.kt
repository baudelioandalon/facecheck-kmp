package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlin.math.ceil

/**
 * Local, non-biometric gate for the INE back side.
 *
 * The SDK camera stream is face-oriented today: it does not expose a document
 * contour detector, and the back of the INE normally has no face to track. This
 * gate therefore uses the available whole-frame quality signal as a pragmatic
 * "readable and stable" check before auto-capturing, while the backend remains
 * the authoritative validator before anything is persisted.
 */
internal class DocumentBackCaptureReadiness(
    private val requiredHoldMs: Long = STABLE_HOLD_MS,
) {
    private var stableSinceMs: Long? = null

    fun onFrame(frame: FaceFrame): State {
        val blocking = frame.blockingInstruction()
        if (blocking != null) {
            stableSinceMs = null
            return State(
                instruction = blocking,
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
                "Reverso de la INE listo"
            } else {
                "Mantén el reverso dentro del rectángulo ${ceil(remainingMs / 1_000.0).toInt()}…"
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
        val stepLabel: String = if (isReady) "Reverso de la INE listo" else "Alineando reverso"
    }

    private fun FaceFrame.blockingInstruction(): String? = when {
        quality.sharpness < MIN_SHARPNESS -> "Acércala un poco o mejora la nitidez"
        quality.brightness < MIN_BRIGHTNESS -> "Busca más luz para leer el reverso"
        quality.brightness > MAX_BRIGHTNESS -> "Hay demasiada luz; evita los reflejos"
        else -> null
    }

    companion object {
        const val STABLE_HOLD_MS = 2_000L
        const val MIN_SHARPNESS = 45f
        const val MIN_BRIGHTNESS = 45f
        const val MAX_BRIGHTNESS = 235f
    }
}
