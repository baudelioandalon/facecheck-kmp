package com.borealnetwork.facecheck.crypto

/** Platform hybrid-encryption implementation shared by all request builders. */
internal expect fun encryptRequestEnvelope(
    plaintext: ByteArray,
    publicKeyPem: String,
    keyId: String,
): String
