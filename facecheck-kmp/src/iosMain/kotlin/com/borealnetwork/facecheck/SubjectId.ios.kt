@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.borealnetwork.facecheck

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.UByteVar
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

actual object SubjectId {
    actual fun generate(apiKey: String): String = formatSubjectId(
        apiKeyHash = sha256(apiKey.encodeToByteArray()),
        randomBytes = secureRandomBytes(),
    )
}

@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
private fun sha256(input: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
    input.usePinned { pinnedInput ->
        digest.usePinned { pinnedDigest ->
            CC_SHA256(
                pinnedInput.addressOf(0),
                input.size.convert(),
                pinnedDigest.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return digest
}

private fun secureRandomBytes(): ByteArray = ByteArray(RANDOM_BYTES).also { bytes ->
    bytes.usePinned { pinned ->
        check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0)) == 0)
    }
}
