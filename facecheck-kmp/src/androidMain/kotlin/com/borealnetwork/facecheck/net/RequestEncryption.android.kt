package com.borealnetwork.facecheck.net

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal actual object RequestEncryption {
    private val secureRandom = SecureRandom()

    actual fun encrypt(plaintext: ByteArray, key: RequestEncryptionKey): EncryptedEnvelope {
        val contentKey = ByteArray(AES_KEY_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)

        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        aes.updateAAD(AAD)
        val encrypted = aes.doFinal(plaintext)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - TAG_BYTES)
        val tag = encrypted.copyOfRange(encrypted.size - TAG_BYTES, encrypted.size)

        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(decodePem(key.publicKeyPem)),
        )
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT,
            ),
        )

        return EncryptedEnvelope(
            version = 1,
            keyId = key.keyId,
            wrappedKey = b64(rsa.doFinal(contentKey)),
            iv = b64(iv),
            ciphertext = b64(ciphertext),
            tag = b64(tag),
        )
    }

    private fun decodePem(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(
            pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString(separator = ""),
        )

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val AES_KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private val AAD = "facecheck-request-v1".encodeToByteArray()
}
