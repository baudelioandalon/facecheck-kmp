package com.borealnetwork.facecheck.crypto

/** Minimal AES-256-GCM primitive for the iOS platform implementation. */
internal data class AesGcmResult(val ciphertext: ByteArray, val tag: ByteArray)

internal expect fun secureRandomBytes(size: Int): ByteArray

private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal fun base64UrlEncode(bytes: ByteArray): String {
    val output = StringBuilder((bytes.size * 8 + 5) / 6)
    var buffer = 0
    var bits = 0
    bytes.forEach { value ->
        buffer = (buffer shl 8) or (value.toInt() and 0xff)
        bits += 8
        while (bits >= 6) {
            bits -= 6
            output.append(BASE64[(buffer ushr bits) and 0x3f])
        }
    }
    if (bits > 0) output.append(BASE64[(buffer shl (6 - bits)) and 0x3f])
    return output.toString()
}

internal fun base64UrlDecode(value: String): ByteArray {
    val result = ArrayList<Byte>(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    value.forEach { char ->
        val digit = BASE64.indexOf(char)
        require(digit >= 0) { "Invalid base64url" }
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            result += (buffer ushr bits).toByte()
        }
    }
    return result.toByteArray()
}

internal fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): AesGcmResult {
    require(key.size == 32 && iv.size == 12)
    val aes = Aes256(key)
    val h = aes.block(ByteArray(16))
    val j0 = iv + byteArrayOf(0, 0, 0, 1)
    val ciphertext = ByteArray(plaintext.size)
    var counter = j0.copyOf()
    var offset = 0
    while (offset < plaintext.size) {
        incrementCounter(counter)
        val stream = aes.block(counter)
        val length = minOf(16, plaintext.size - offset)
        for (i in 0 until length) ciphertext[offset + i] = (plaintext[offset + i].toInt() xor stream[i].toInt()).toByte()
        offset += length
    }
    val s = gHash(h, aad, ciphertext)
    val tagMask = aes.block(j0)
    return AesGcmResult(ciphertext, ByteArray(16) { (tagMask[it].toInt() xor s[it].toInt()).toByte() })
}

private class Aes256(key: ByteArray) {
    private val roundKeys: ByteArray = expandKey(key)

    fun block(input: ByteArray): ByteArray {
        val state = input.copyOf()
        addRoundKey(state, 0)
        for (round in 1 until 14) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, 14)
        return state
    }

    private fun addRoundKey(state: ByteArray, round: Int) {
        for (i in 0 until 16) state[i] = (state[i].toInt() xor roundKeys[round * 16 + i].toInt()).toByte()
    }

    private fun subBytes(state: ByteArray) { for (i in state.indices) state[i] = SBOX[state[i].toInt() and 0xff].toByte() }

    private fun shiftRows(state: ByteArray) {
        val copy = state.copyOf()
        for (column in 0 until 4) for (row in 0 until 4) state[column * 4 + row] = copy[((column + row) % 4) * 4 + row]
    }

    private fun mixColumns(state: ByteArray) {
        for (column in 0 until 4) {
            val i = column * 4
            val a0 = state[i].toInt() and 0xff
            val a1 = state[i + 1].toInt() and 0xff
            val a2 = state[i + 2].toInt() and 0xff
            val a3 = state[i + 3].toInt() and 0xff
            state[i] = (mul2(a0) xor mul3(a1) xor a2 xor a3).toByte()
            state[i + 1] = (a0 xor mul2(a1) xor mul3(a2) xor a3).toByte()
            state[i + 2] = (a0 xor a1 xor mul2(a2) xor mul3(a3)).toByte()
            state[i + 3] = (mul3(a0) xor a1 xor a2 xor mul2(a3)).toByte()
        }
    }

    private fun expandKey(key: ByteArray): ByteArray {
        val expanded = ByteArray(240)
        key.copyInto(expanded)
        var bytes = 32
        var rcon = 1
        val temp = ByteArray(4)
        while (bytes < expanded.size) {
            for (i in 0 until 4) temp[i] = expanded[bytes - 4 + i]
            if (bytes % 32 == 0) {
                val first = temp[0]
                temp[0] = SBOX[temp[1].toInt() and 0xff].toByte()
                temp[1] = SBOX[temp[2].toInt() and 0xff].toByte()
                temp[2] = SBOX[temp[3].toInt() and 0xff].toByte()
                temp[3] = SBOX[first.toInt() and 0xff].toByte()
                temp[0] = (temp[0].toInt() xor rcon).toByte()
                rcon = xtime(rcon)
            } else if (bytes % 32 == 16) {
                for (i in 0 until 4) temp[i] = SBOX[temp[i].toInt() and 0xff].toByte()
            }
            for (i in 0 until 4) {
                expanded[bytes] = (expanded[bytes - 32].toInt() xor temp[i].toInt()).toByte()
                bytes++
            }
        }
        return expanded
    }

    private companion object {
        val SBOX = intArrayOf(
            0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
            0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
            0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
            0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
            0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
            0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
            0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
            0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
            0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
            0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
            0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
            0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
            0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
            0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
            0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
            0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16,
        )
        fun xtime(value: Int): Int = ((value shl 1) xor if (value and 0x80 != 0) 0x11b else 0) and 0xff
        fun mul2(value: Int): Int = xtime(value)
        fun mul3(value: Int): Int = xtime(value) xor value
    }
}

private fun incrementCounter(counter: ByteArray) {
    for (index in 15 downTo 12) {
        counter[index] = (counter[index].toInt() + 1).toByte()
        if (counter[index].toInt() and 0xff != 0) break
    }
}

private fun gHash(h: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
    var y = ULongPair(0u, 0u)
    fun absorb(data: ByteArray) {
        for (offset in data.indices step 16) {
            val block = ByteArray(16)
            data.copyInto(block, 0, offset, minOf(offset + 16, data.size))
            y = multiply(y xor block.toPair(), h.toPair())
        }
    }
    absorb(aad)
    absorb(ciphertext)
    val lengths = ByteArray(16)
    val aadBits = aad.size.toULong() * 8u
    val ciphertextBits = ciphertext.size.toULong() * 8u
    lengths.writeUlong(0, aadBits)
    lengths.writeUlong(8, ciphertextBits)
    return multiply(y xor lengths.toPair(), h.toPair()).toBytes()
}

private data class ULongPair(val high: ULong, val low: ULong) {
    infix fun xor(other: ULongPair) = ULongPair(high xor other.high, low xor other.low)
    infix fun xor(other: BytePair) = ULongPair(high xor other.high, low xor other.low)
}
private data class BytePair(val high: ULong, val low: ULong)
private fun ByteArray.toPair() = BytePair(readUlong(0), readUlong(8))
private fun ByteArray.readUlong(offset: Int): ULong = (0 until 8).fold(0uL) { acc, i -> (acc shl 8) or (this[offset + i].toULong() and 0xffu) }
private fun ByteArray.writeUlong(offset: Int, value: ULong) { for (i in 0 until 8) this[offset + i] = (value shr (56 - i * 8)).toByte() }
private fun ULongPair.toBytes(): ByteArray = ByteArray(16).also { it.writeUlong(0, high); it.writeUlong(8, low) }
private fun multiply(x: ULongPair, y: BytePair): ULongPair {
    var z = ULongPair(0u, 0u)
    var v = ULongPair(y.high, y.low)
    for (bit in 0 until 128) {
        val set = if (bit < 64) (x.high shr (63 - bit)) and 1u else (x.low shr (127 - bit)) and 1u
        if (set == 1uL) z = z xor v
        val lsb = v.low and 1uL
        v = ULongPair(v.high shr 1, (v.low shr 1) or (v.high shl 63))
        if (lsb == 1uL) v = ULongPair(v.high xor 0xe100000000000000uL, v.low)
    }
    return z
}
