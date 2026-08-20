package com.borealnetwork.facecheck.sample

internal object CaptureFailurePresentation {
    @Suppress("UNUSED_PARAMETER")
    fun fromUnexpected(error: Throwable): String =
        "No pudimos iniciar la sesión. Revisa permisos, luz y conexión; después intenta de nuevo."
}
