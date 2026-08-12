package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * Quality signals the backend computed on the image it actually processed.
 *
 * These describe the caller's own photo and say nothing about the enrolled
 * subject, which is why the backend still returns them while it withholds match
 * scores. Their whole purpose is coaching a retry: see [hintEs].
 *
 * Mirrors `FaceQuality` in `functions-python/facecheck/engine.py`. Every field is
 * defaulted so a backend that starts reporting one more signal does not break
 * apps compiled against this version.
 */
@Serializable
data class FaceQuality(
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val faceWidth: Int = 0,
    val faceHeight: Int = 0,
    /** Face width as a fraction of image width. Below ~0.20 the crop is starved. */
    val faceRatio: Float = 0f,
    /** MTCNN's confidence for the detected box, 0..1. */
    val detectorScore: Float = 0f,
    /** Variance of the Laplacian on the aligned crop. Higher is sharper. */
    val sharpness: Float = 0f,
    /** Mean luminance of the aligned crop, 0..255. */
    val brightness: Float = 0f,
    /** Whether the five-landmark alignment succeeded. */
    val aligned: Boolean = false,
) {
    /**
     * The one thing to tell the user before they retry, or null when nothing
     * about the image stands out as the reason a match failed.
     *
     * Ordered by how much each defect actually costs the embedding: a face too
     * small to crop cleanly beats a blurry one, which beats a dark one.
     */
    val hintEs: String?
        get() = when {
            faceRatio > 0f && faceRatio < LOW_FACE_RATIO ->
                "Acércate más a la cámara: tu rostro se ve muy pequeño."
            sharpness > 0f && sharpness < LOW_SHARPNESS ->
                "La foto salió borrosa. Sostén el teléfono firme y vuelve a intentar."
            brightness > 0f && brightness < LOW_BRIGHTNESS ->
                "Hay poca luz. Colócate frente a una fuente de luz."
            brightness > HIGH_BRIGHTNESS ->
                "Hay demasiada luz de fondo. Evita ventanas o lámparas detrás de ti."
            !aligned ->
                "No se pudo alinear tu rostro. Mira de frente a la cámara."
            else -> null
        }

    private companion object {
        const val LOW_FACE_RATIO = 0.20f
        const val LOW_SHARPNESS = 40f
        const val LOW_BRIGHTNESS = 55f
        const val HIGH_BRIGHTNESS = 215f
    }
}