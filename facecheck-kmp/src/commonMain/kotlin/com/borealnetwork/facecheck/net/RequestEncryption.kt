package com.borealnetwork.facecheck.net

import kotlinx.serialization.Serializable

@Serializable
internal data class RequestEncryptionKey(
    val keyId: String,
    val publicKeyPem: String,
    val version: Int = 1,
)

@Serializable
internal data class EncryptedEnvelope(
    val version: Int,
    val keyId: String,
    val wrappedKey: String,
    val iv: String,
    val ciphertext: String,
    val tag: String,
)

@Serializable
internal data class EncryptedJsonRequest(
    val keyId: String,
    val encryptedPayload: EncryptedEnvelope,
)

internal expect object RequestEncryption {
    fun encrypt(plaintext: ByteArray, key: RequestEncryptionKey): EncryptedEnvelope
}
