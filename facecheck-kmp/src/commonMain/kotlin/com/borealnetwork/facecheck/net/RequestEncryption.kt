package com.borealnetwork.facecheck.net

internal expect object RequestEncryption {
    fun encrypt(plaintext: ByteArray, key: RequestEncryptionKey): EncryptedEnvelope
}
