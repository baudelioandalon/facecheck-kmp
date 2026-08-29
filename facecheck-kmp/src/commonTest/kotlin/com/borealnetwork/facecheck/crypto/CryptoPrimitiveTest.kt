package com.borealnetwork.facecheck.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CryptoPrimitiveTest {

    @Test
    fun sha256_matches_the_standard_abc_vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun aes256_gcm_matches_the_nist_zero_vector() {
        val result = aesGcmEncrypt(
            key = ByteArray(32),
            iv = ByteArray(12),
            plaintext = ByteArray(16),
            aad = ByteArray(0),
        )

        assertContentEquals(
            hex("cea7403d4d606b6e074ec5d3baf39d18"),
            result.ciphertext,
        )
        assertContentEquals(
            hex("d0d1c8a799996bf0265b98b5d48ab919"),
            result.tag,
        )
    }

    @Test
    fun base64url_round_trips_binary_data_without_padding() {
        val input = byteArrayOf(0, 1, 2, 0xfb.toByte(), 0xff.toByte())
        assertContentEquals(input, base64UrlDecode(base64UrlEncode(input)))
    }
}

private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
