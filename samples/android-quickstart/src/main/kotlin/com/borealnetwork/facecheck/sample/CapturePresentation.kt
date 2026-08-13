package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.LivenessState

internal data class CapturePresentation(
    val instruction: String,
    val stepLabel: String,
    val progress: Float,
    val isFinalizing: Boolean = false,
) {
    val ringProgress: Float = progress.coerceIn(0f, 1f)

    companion object {
        fun from(
            state: LivenessState,
            finalizingInstruction: String = "Guardando enrolamiento…",
        ): CapturePresentation = when (state) {
            is LivenessState.ChallengeActive -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = "Paso ${state.index + 1} de ${state.total}",
                progress = state.progress,
            )
            is LivenessState.Positioning -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = "Alinea tu rostro",
                progress = state.holdProgress,
            )
            LivenessState.Capturing -> CapturePresentation(
                instruction = finalizingInstruction,
                stepLabel = "Pasos completados",
                progress = 1f,
                isFinalizing = true,
            )
            else -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = "Preparando",
                progress = state.progress,
            )
        }
    }
}
