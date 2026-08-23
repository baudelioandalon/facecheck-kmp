package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.FaceFrame
import kotlin.math.ceil
import kotlin.math.abs

/** Local, non-biometric gate for the INE front side before the first capture. */
internal class DocumentCaptureReadiness(
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
                "Frente de la INE lista"
            } else {
                "Mantén el frente de la INE dentro del rectángulo ${ceil(remainingMs / 1_000.0).toInt()}…"
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
        val stepLabel: String = if (isReady) "Frente de la INE lista" else "Alineando INE"
    }

    private fun FaceFrame.blockingInstruction(): String? = when {
        faceCount == 0 -> "Coloca el frente de la INE dentro del rectángulo"
        faceCount > 1 -> "Solo debe aparecer una credencial"
        !insideGuide -> "Centra la INE dentro del rectángulo"
        !isFrontalEnough() -> "Mantén la credencial de frente"
        quality.sharpness < MIN_SHARPNESS -> "Acércala un poco o mejora la nitidez"
        quality.brightness < MIN_BRIGHTNESS -> "Busca más luz para leer la INE"
        quality.brightness > MAX_BRIGHTNESS -> "Hay demasiada luz; evita los reflejos"
        else -> null
    }

    private fun FaceFrame.isFrontalEnough(): Boolean =
        abs(yaw) <= MAX_FRONTAL_YAW && abs(pitch) <= MAX_FRONTAL_PITCH && abs(roll) <= MAX_ROLL

    companion object {
        const val STABLE_HOLD_MS = 3_000L
        const val MAX_FRONTAL_YAW = 12f
        const val MAX_FRONTAL_PITCH = 12f
        const val MAX_ROLL = 10f
        const val MIN_SHARPNESS = 70f
        const val MIN_BRIGHTNESS = 60f
        const val MAX_BRIGHTNESS = 220f
    }
}
