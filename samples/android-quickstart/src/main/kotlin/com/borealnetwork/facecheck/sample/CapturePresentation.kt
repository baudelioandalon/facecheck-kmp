package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.liveness.LivenessState

internal data class CapturePresentation(
    val instruction: String,
    val stepLabel: String,
    val progress: Float,
) {
    val ringProgress: Float = progress.coerceIn(0f, 1f)

    companion object {
        fun from(state: LivenessState): CapturePresentation = when (state) {
            is LivenessState.ChallengeActive -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = "Reto ${state.index + 1} de ${state.total}",
                progress = state.progress,
            )
            is LivenessState.Positioning -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = "Alinea tu rostro",
                progress = state.holdProgress,
            )
            else -> CapturePresentation(
                instruction = state.instructionEs,
                stepLabel = if (state is LivenessState.Capturing) "Capturando" else "Preparando",
                progress = state.progress,
            )
        }
    }
}
