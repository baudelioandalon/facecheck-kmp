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
            is LivenessState.CapturingEvidence -> evidencePresentation(state)
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

        private fun evidencePresentation(state: LivenessState.CapturingEvidence): CapturePresentation =
            when (state.role) {
                com.borealnetwork.facecheck.liveness.EvidenceRole.FRONT_INITIAL -> CapturePresentation(
                    instruction = "Mantén tu rostro dentro del óvalo",
                    stepLabel = "Preparando captura",
                    progress = 0f,
                )
                com.borealnetwork.facecheck.liveness.EvidenceRole.TURN_FIRST -> CapturePresentation(
                    instruction = "No te muevas, estamos tomando la foto",
                    stepLabel = "Paso 1 de 3",
                    progress = 1f / 3f,
                )
                com.borealnetwork.facecheck.liveness.EvidenceRole.CENTER_BETWEEN -> CapturePresentation(
                    instruction = "Regresa a ver de frente",
                    stepLabel = "Centrando rostro",
                    progress = .5f,
                )
                com.borealnetwork.facecheck.liveness.EvidenceRole.TURN_SECOND -> CapturePresentation(
                    instruction = "No te muevas, estamos tomando la foto",
                    stepLabel = "Paso 2 de 3",
                    progress = 2f / 3f,
                )
                com.borealnetwork.facecheck.liveness.EvidenceRole.FRONT_FINAL -> CapturePresentation(
                    instruction = "No te muevas, estamos tomando la foto",
                    stepLabel = "Paso 3 de 3",
                    progress = 1f,
                )
            }
    }
}
