package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException

internal actual object RequestEncryption {
    actual fun encrypt(plaintext: ByteArray, key: RequestEncryptionKey): EncryptedEnvelope {
        throw FaceCheckException(
            code = FaceCheckErrorCode.INVALID_CONFIG,
            message = "El cifrado de requests aún no está implementado en iOS.",
        )
    }
}
