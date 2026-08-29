package com.borealnetwork.facecheck.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun encryptRequestEnvelope(
    plaintext: ByteArray,
    publicKeyPem: String,
    keyId: String,
): String {
    val contentKey = secureRandomBytes(32)
    val iv = secureRandomBytes(12)
    val result = aesGcmEncrypt(contentKey, iv, plaintext, "facecheck-request-v1".encodeToByteArray())
    val wrappedKey = rsaOaepSha256(publicKeyPem, contentKey)
    return "{" +
        "\"version\":1," +
        "\"keyId\":\"${keyId.escapeJson()}\"," +
        "\"wrappedKey\":\"${base64UrlEncode(wrappedKey)}\"," +
        "\"iv\":\"${base64UrlEncode(iv)}\"," +
        "\"ciphertext\":\"${base64UrlEncode(result.ciphertext)}\"," +
        "\"tag\":\"${base64UrlEncode(result.tag)}\"}"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
    check(status == 0) { "No se pudo generar aleatoriedad segura." }
}

@OptIn(ExperimentalForeignApi::class)
private fun rsaOaepSha256(publicKeyPem: String, plaintext: ByteArray): ByteArray {
    // The control plane emits X.509 SubjectPublicKeyInfo. SecKeyCreateWithData
    // expects the RSA PKCS#1 public-key payload, so unwrap the BIT STRING before
    // handing it to Security.
    val der = publicKeyPem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace(Regex("\\s"), "")
        .trimEnd('=')
    val derBytes = base64UrlDecode(der.replace('+', '-').replace('/', '_'))
    val pkcs1 = subjectPublicKeyInfoPayload(derBytes)
    val data = pkcs1.toCFData()
    val attributes = CFDictionaryCreateMutable(null, 3, null, null)
    CFDictionarySetValue(attributes, platform.Security.kSecAttrKeyType, platform.Security.kSecAttrKeyTypeRSA)
    CFDictionarySetValue(attributes, platform.Security.kSecAttrKeyClass, platform.Security.kSecAttrKeyClassPublic)
    val key = platform.Security.SecKeyCreateWithData(data, attributes, null)
        ?: error("No se pudo crear la clave pública RSA.")
    val encrypted = plaintext.toCFData()
    val output = platform.Security.SecKeyCreateEncryptedData(
        key,
        platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA256,
        encrypted,
        null,
    ) ?: error("No se pudo envolver la clave AES con RSA.")
    return output.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef = usePinned {
    CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert())!!
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val size = CFDataGetLength(this).toInt()
    val output = ByteArray(size)
    if (size > 0) output.usePinned { platform.posix.memcpy(it.addressOf(0), CFDataGetBytePtr(this), size.convert()) }
    return output
}

/** Extract the RSA PKCS#1 key from an X.509 SubjectPublicKeyInfo DER value. */
private fun subjectPublicKeyInfoPayload(der: ByteArray): ByteArray {
    val outer = der.readDerElement(0, 0x30)
    val algorithm = outer.value.readDerElement(0, 0x30)
    val bitString = outer.value.readDerElement(algorithm.next, 0x03)
    require(bitString.value.isNotEmpty() && bitString.value[0].toInt() == 0) {
        "La clave pública RSA no contiene un BIT STRING válido."
    }
    return bitString.value.copyOfRange(1, bitString.value.size)
}

private data class DerElement(val value: ByteArray, val next: Int)

private fun ByteArray.readDerElement(start: Int, expectedTag: Int): DerElement {
    require(start + 2 <= size && (this[start].toInt() and 0xff) == expectedTag) {
        "La clave pública RSA no contiene DER válido."
    }
    var cursor = start + 1
    val firstLength = this[cursor++].toInt() and 0xff
    val length = if (firstLength and 0x80 == 0) {
        firstLength
    } else {
        val count = firstLength and 0x7f
        require(count in 1..4 && cursor + count <= size) { "Longitud DER inválida." }
        var parsed = 0
        repeat(count) { parsed = (parsed shl 8) or (this[cursor++].toInt() and 0xff) }
        parsed
    }
    val end = cursor + length
    require(end <= size) { "Datos DER incompletos." }
    return DerElement(copyOfRange(cursor, end), end)
}

private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
