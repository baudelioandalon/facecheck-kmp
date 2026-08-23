package com.borealnetwork.facecheck.sample

internal object DocumentCaptureAutomation {
    data class State(
        val frontCaptured: Boolean,
        val backCaptured: Boolean,
        val uploading: Boolean,
    ) {
        val shouldAutoCaptureBack: Boolean =
            frontCaptured && !backCaptured && !uploading

        val shouldAutoUpload: Boolean =
            frontCaptured && backCaptured && !uploading

        val stepLabel: String = when {
            uploading -> "Guardando INE"
            shouldAutoUpload -> "Enviando INE"
            shouldAutoCaptureBack -> "Paso 2 de 2"
            frontCaptured -> "Reverso capturado"
            else -> "Alineando INE"
        }
    }
}
