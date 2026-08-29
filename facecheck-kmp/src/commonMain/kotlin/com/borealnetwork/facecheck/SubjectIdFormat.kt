package com.borealnetwork.facecheck

/** The backend contract for caller-provided subject IDs. */
internal val SUBJECT_ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{7,127}$")

/** The deterministic generated-ID envelope, shared by Android and iOS tests. */
internal val GENERATED_SUBJECT_ID_PATTERN = Regex("^sub_[A-Z2-7]{10}_[A-Za-z0-9_-]{22}$")

internal fun isValidSubjectId(subjectId: String): Boolean = SUBJECT_ID_PATTERN.matches(subjectId)

internal fun formatSubjectId(apiKeyHash: ByteArray, randomBytes: ByteArray): String {
    require(apiKeyHash.isNotEmpty()) { "SHA-256 must produce bytes." }
    require(randomBytes.size == RANDOM_BYTES) { "Subject IDs require $RANDOM_BYTES random bytes." }
    return "sub_${apiKeyHash.toBase32().take(FINGERPRINT_LENGTH)}_${randomBytes.toBase64Url()}"
}

private fun ByteArray.toBase32(): String {
    val output = StringBuilder((size * 8 + 4) / 5)
    var buffer = 0
    var bits = 0
    for (byte in this) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            output.append(BASE32_ALPHABET[(buffer shr (bits - 5)) and 31])
            bits -= 5
        }
    }
    if (bits > 0) output.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
    return output.toString()
}

private fun ByteArray.toBase64Url(): String {
    val output = StringBuilder((size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < size) {
        val block = ((this[index].toInt() and 0xff) shl 16) or
            ((this[index + 1].toInt() and 0xff) shl 8) or
            (this[index + 2].toInt() and 0xff)
        output.append(BASE64_URL_ALPHABET[(block ushr 18) and 63])
        output.append(BASE64_URL_ALPHABET[(block ushr 12) and 63])
        output.append(BASE64_URL_ALPHABET[(block ushr 6) and 63])
        output.append(BASE64_URL_ALPHABET[block and 63])
        index += 3
    }
    when (size - index) {
        1 -> {
            val block = (this[index].toInt() and 0xff) shl 16
            output.append(BASE64_URL_ALPHABET[(block ushr 18) and 63])
            output.append(BASE64_URL_ALPHABET[(block ushr 12) and 63])
        }
        2 -> {
            val block = ((this[index].toInt() and 0xff) shl 16) or
                ((this[index + 1].toInt() and 0xff) shl 8)
            output.append(BASE64_URL_ALPHABET[(block ushr 18) and 63])
            output.append(BASE64_URL_ALPHABET[(block ushr 12) and 63])
            output.append(BASE64_URL_ALPHABET[(block ushr 6) and 63])
        }
    }
    return output.toString()
}

private const val FINGERPRINT_LENGTH = 10
internal const val RANDOM_BYTES = 16
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val BASE64_URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
