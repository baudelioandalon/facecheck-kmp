package com.borealnetwork.facecheck.sample

/** Keeps the UI from starting CameraX or a FaceCheck request before its prerequisites are met. */
internal object SessionPreconditions {
    fun blockingMessage(apiKey: String, sdkInt: Int, granted: Set<String>): String? = when {
        apiKey.isBlank() -> "Configura FACECHECK_API_KEY en local.properties antes de continuar."
        !RequiredPermissions.isSatisfied(sdkInt, granted) ->
            "Acepta cámara, almacenamiento y ubicación antes de iniciar."
        else -> null
    }
}
