package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.crypto.encryptRequestEnvelope
import kotlinx.serialization.json.Json

internal actual object RequestEncryption {
    actual fun encrypt(plaintext: ByteArray, key: RequestEncryptionKey): EncryptedEnvelope =
        Json.decodeFromString(
            encryptRequestEnvelope(
                plaintext = plaintext,
                publicKeyPem = key.publicKeyPem,
                keyId = key.keyId,
            ),
        )
}
