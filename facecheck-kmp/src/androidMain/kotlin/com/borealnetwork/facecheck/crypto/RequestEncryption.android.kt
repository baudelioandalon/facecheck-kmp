package com.borealnetwork.facecheck.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val IV_BYTES = 12
private const val TAG_BYTES = 16
private val AAD = "facecheck-request-v1".toByteArray(StandardCharsets.UTF_8)

internal actual fun encryptRequestEnvelope(
    plaintext: ByteArray,
    publicKeyPem: String,
    keyId: String,
): String {
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(pemBytes(publicKeyPem)),
    )
    val contentKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
    val aes = Cipher.getInstance("AES/GCM/NoPadding")
    aes.init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(TAG_BYTES * 8, iv))
    aes.updateAAD(AAD)
    val encrypted = aes.doFinal(plaintext)

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
    val wrappedKey = rsa.doFinal(contentKey.encoded)
    val ciphertext = encrypted.copyOfRange(0, encrypted.size - TAG_BYTES)
    val tag = encrypted.copyOfRange(encrypted.size - TAG_BYTES, encrypted.size)
    return "{" +
        "\"version\":1," +
        "\"keyId\":\"${keyId.escapeJson()}\"," +
        "\"wrappedKey\":\"${wrappedKey.urlBase64()}\"," +
        "\"iv\":\"${iv.urlBase64()}\"," +
        "\"ciphertext\":\"${ciphertext.urlBase64()}\"," +
        "\"tag\":\"${tag.urlBase64()}\"}"
}

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

private fun pemBytes(pem: String): ByteArray {
    val body = pem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace(Regex("\\s"), "")
    return Base64.getDecoder().decode(body)
}

private fun ByteArray.urlBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
