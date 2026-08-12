package com.borealnetwork.facecheck

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceCheckLoggerTest {

    private val lines = mutableListOf<String>()

    private fun capture(at: FaceCheckLogLevel) {
        lines.clear()
        FaceCheckLogger.level = at
        FaceCheckLogger.sink = FaceCheckLogSink { _, message -> lines += message }
    }

    @AfterTest
    fun tearDown() {
        FaceCheckLogger.level = FaceCheckLogLevel.NONE
    }

    @Test
    fun an_api_key_never_reaches_the_sink() {
        // The leak that actually happens is the one nobody wrote on purpose: a
        // server error echoed verbatim, or a URL inside an exception message.
        capture(FaceCheckLogLevel.DEBUG)

        FaceCheckLogger.info { "POST https://api/enroll key=lk_live_abc123XYZ_-456 failed" }

        val line = lines.single()
        assertFalse(line.contains("abc123XYZ"), "the key survived redaction: $line")
        assertContains(line, "lk_live_***")
    }

    @Test
    fun both_key_prefixes_are_masked() {
        capture(FaceCheckLogLevel.DEBUG)

        FaceCheckLogger.warn { "keys: lk_test_aaaaaaaaaaaa and lk_live_bbbbbbbbbbbb" }

        assertEquals("keys: lk_test_*** and lk_live_***", lines.single())
    }

    @Test
    fun an_email_keeps_only_its_domain() {
        capture(FaceCheckLogLevel.DEBUG)

        FaceCheckLogger.info { "verify for persona.ejemplo@gmail.com" }

        val line = lines.single()
        assertFalse(line.contains("ejemplo"), "the local part survived: $line")
        assertContains(line, "p***@gmail.com")
    }

    @Test
    fun image_data_can_only_be_described_by_size() {
        // There is no overload that takes a ByteArray, so "log the selfie" is not
        // something a caller can express in the first place.
        assertEquals("<204800 bytes>", FaceCheckLogger.describeBytes(204_800))
    }

    @Test
    fun nothing_is_emitted_at_the_default_level() {
        capture(FaceCheckLogLevel.NONE)

        FaceCheckLogger.error { "boom" }
        FaceCheckLogger.debug { "chatter" }

        assertTrue(lines.isEmpty(), "expected silence, got $lines")
    }

    @Test
    fun a_level_admits_everything_at_or_above_its_severity() {
        capture(FaceCheckLogLevel.WARN)

        FaceCheckLogger.error { "error" }
        FaceCheckLogger.warn { "warn" }
        FaceCheckLogger.info { "info" }
        FaceCheckLogger.debug { "debug" }

        assertEquals(listOf("error", "warn"), lines)
    }

    @Test
    fun a_suppressed_message_is_never_even_built() {
        capture(FaceCheckLogLevel.ERROR)
        var built = 0

        FaceCheckLogger.debug { built++; "expensive" }

        assertEquals(0, built, "the lambda ran despite the level being off")
    }
}