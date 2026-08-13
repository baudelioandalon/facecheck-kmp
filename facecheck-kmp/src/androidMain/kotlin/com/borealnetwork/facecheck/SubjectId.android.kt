package com.borealnetwork.facecheck

import java.security.MessageDigest
import java.security.SecureRandom

actual object SubjectId {
    actual fun generate(apiKey: String): String = formatSubjectId(
        apiKeyHash = MessageDigest.getInstance("SHA-256").digest(apiKey.encodeToByteArray()),
        randomBytes = ByteArray(RANDOM_BYTES).also(SecureRandom()::nextBytes),
    )
}
