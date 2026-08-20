package com.borealnetwork.facecheck.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.borealnetwork.facecheck.liveness.CapturedJpeg
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Turns an `ImageCapture` result into the JPEG the backend will actually see.
 *
 * ### Why it decodes and re-encodes instead of forwarding the bytes
 *
 * `ImageCapture` already hands back JPEG, so copying the buffer out would be
 * cheaper. It would also be wrong twice over:
 *
 *  1. **Orientation.** The capture is in sensor orientation with the correct
 *     angle recorded in an EXIF tag. Whether that tag is honoured depends
 *     entirely on what opens the file, and the backend's pipeline decodes with
 *     OpenCV, which ignores EXIF orientation outright. A portrait selfie would
 *     arrive rotated 90°, MTCNN would find no face in it, and the call would
 *     come back `NO_FACE` on a perfectly good photo. Rotating the pixels and
 *     re-encoding without the tag makes the bytes mean the same thing to every
 *     reader.
 *  2. **Size.** A 12 MP still is several megabytes of upload for a network the
 *     user is standing in a queue on, to be resized on arrival anyway.
 *
 * The still is never mirrored — CameraX delivers the front camera un-mirrored
 * and it stays that way. A mirrored face matches its own template perfectly
 * well, but the reference photo is looked at by humans during disputes, and one
 * that is flipped relative to the ID next to it reads as a different person.
 */
internal object StillEncoder {

    /**
     * @param proxy a JPEG-format capture; closed by the caller, not here.
     * @return upright JPEG bytes, longest edge at most
     *   [CameraOptions.targetStillSize].
     */
    fun encode(proxy: ImageProxy, options: CameraOptions): CapturedJpeg {
        val raw = proxy.planes.firstOrNull()?.buffer
            ?: throw captureFailed("La cámara devolvió una imagen vacía.")
        val bytes = ByteArray(raw.remaining()).also { raw.get(it) }

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw captureFailed("No se pudo decodificar la foto de la cámara.")

        val upright = transform(decoded, proxy.imageInfo.rotationDegrees, options.targetStillSize)
        return try {
            val sink = ByteArrayOutputStream(bytes.size)
            if (!upright.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality, sink)) {
                throw captureFailed("No se pudo comprimir la foto.")
            }
            CapturedJpeg(
                bytes = sink.toByteArray(),
                width = upright.width,
                height = upright.height,
            )
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    /**
     * Rotate to upright and scale down, in one matrix so the bitmap is only
     * allocated once.
     */
    private fun transform(source: Bitmap, rotationDegrees: Int, targetLongestEdge: Int): Bitmap {
        val longestEdge = max(source.width, source.height)
        val scale = if (longestEdge > targetLongestEdge) {
            targetLongestEdge.toFloat() / longestEdge
        } else {
            // Never upscale: inventing pixels does not give the detector more to
            // work with, it only makes the upload bigger.
            1f
        }
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (scale == 1f && normalizedRotation == 0) return source

        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (normalizedRotation != 0) postRotate(normalizedRotation.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun captureFailed(message: String) = FaceCheckException(
        code = FaceCheckErrorCode.CAMERA_UNAVAILABLE,
        message = message,
    )
}
