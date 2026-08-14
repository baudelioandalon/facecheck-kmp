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
                stepLabel = positioningLabel(state.holdProgress),
                progress = state.holdProgress,
            )
            LivenessState.Capturing,
            is LivenessState.Done -> CapturePresentation(
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

        private fun positioningLabel(progress: Float): String {
            val seconds = (3 - (progress.coerceIn(0f, .999f) * 3).toInt()).coerceIn(1, 3)
            return "Mantén el rostro dentro del óvalo · $seconds"
        }
    }
}
