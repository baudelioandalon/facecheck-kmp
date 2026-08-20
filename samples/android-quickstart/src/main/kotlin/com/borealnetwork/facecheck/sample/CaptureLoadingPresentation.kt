package com.borealnetwork.facecheck.sample

internal data class CaptureLoadingPresentation(
    val title: String,
    val body: String,
) {
    companion object {
        fun preparing(operation: SampleOperation): CaptureLoadingPresentation =
            if (operation == SampleOperation.ENROLL) {
                CaptureLoadingPresentation(
                    title = "Preparando sesión segura…",
                    body = "Confirmamos permisos, ubicación y conexión antes de capturar.",
                )
            } else {
                CaptureLoadingPresentation(
                    title = "Preparando verificación…",
                    body = "Confirmamos permisos, ubicación y conexión antes de capturar.",
                )
            }

        fun finalizing(operation: SampleOperation): CaptureLoadingPresentation =
            if (operation == SampleOperation.ENROLL) {
                CaptureLoadingPresentation(
                    title = "Guardando enrolamiento…",
                    body = "Tus tres pasos se completaron. Protegemos el registro antes de continuar.",
                )
            } else {
                CaptureLoadingPresentation(
                    title = "Verificando identidad…",
                    body = "Completamos los pasos. Estamos protegiendo la verificación antes de continuar.",
                )
            }
    }
}
