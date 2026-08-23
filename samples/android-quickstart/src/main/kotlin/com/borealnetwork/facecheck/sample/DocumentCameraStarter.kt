package com.borealnetwork.facecheck.sample

/** Minimal hook so the INE flow always starts the rear camera explicitly. */
internal fun interface DocumentCameraStarter {
    fun start()
}

internal fun startDocumentCamera(starter: DocumentCameraStarter) {
    starter.start()
}
