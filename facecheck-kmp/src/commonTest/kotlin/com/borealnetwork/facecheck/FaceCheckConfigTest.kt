package com.borealnetwork.facecheck

import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_KEY = "lk_test_a1b2c3d4e5f6g7h8"
private const val LIVE_KEY = "lk_live_a1b2c3d4e5f6g7h8"
private const val BASE_URL = "https://facecheck.example.com"

class FaceCheckConfigTest {

    @Test
    fun derives_the_mode_from_the_key_prefix() {
        assertEquals(
            FaceCheckMode.TEST,
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL).mode,
        )
        assertEquals(
            FaceCheckMode.LIVE,
            FaceCheckConfig(apiKey = LIVE_KEY, baseUrl = BASE_URL).mode,
        )
    }

    @Test
    fun rejects_a_key_without_a_recognised_prefix() {
        val failure = assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = "sk_live_a1b2c3d4e5f6g7h8", baseUrl = BASE_URL)
        }
        assertEquals(FaceCheckErrorCode.INVALID_CONFIG, failure.code)
    }

    @Test
    fun rejects_a_truncated_key_or_one_with_whitespace() {
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = "lk_test_", baseUrl = BASE_URL)
        }
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = "lk_test_a1b2 c3d4e5f6g7", baseUrl = BASE_URL)
        }
    }

    @Test
    fun refuses_a_live_key_over_cleartext_http() {
        // A production key on http would put a selfie and an email on the wire in
        // the clear. Refused rather than warned about: a warning gets ignored.
        val failure = assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = LIVE_KEY, baseUrl = "http://facecheck.example.com")
        }
        assertEquals(FaceCheckErrorCode.INVALID_CONFIG, failure.code)

        // A test key against a local emulator is exactly what http is for.
        FaceCheckConfig(apiKey = TEST_KEY, baseUrl = "http://127.0.0.1:5001")
    }

    @Test
    fun rejects_a_base_url_that_is_not_http() {
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = "facecheck.example.com")
        }
    }

    @Test
    fun builds_endpoints_without_doubling_slashes() {
        val config = FaceCheckConfig(apiKey = TEST_KEY, baseUrl = "$BASE_URL/")
        assertEquals("$BASE_URL/enroll", config.endpoint("enroll"))
        assertEquals("$BASE_URL/verify", config.endpoint("/verify"))
    }

    @Test
    fun rejects_a_challenge_count_outside_the_supported_range() {
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL, challengeCount = 0)
        }
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL, challengeCount = 99)
        }
    }

    @Test
    fun toString_never_carries_the_whole_key() {
        // A config ends up in crash reports and bug tickets; the generated
        // data-class rendering would take the API key along with it.
        val rendered = FaceCheckConfig(apiKey = LIVE_KEY, baseUrl = BASE_URL).toString()
        assertFalse(rendered.contains(LIVE_KEY), "toString leaked the key: $rendered")
        assertTrue(rendered.contains("lk_live_a1b2"), "expected the portal's preview form")
    }
}

class FaceCheckInitializeTest {

    @BeforeTest
    fun reset() = FaceCheck.shutdown()

    @AfterTest
    fun tearDown() = FaceCheck.shutdown()

    @Test
    fun initialize_is_idempotent_for_an_identical_config() {
        val config = FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL)
        FaceCheck.initialize(config)
        FaceCheck.initialize(config.copy())

        assertTrue(FaceCheck.isInitialized)
        assertEquals(config, FaceCheck.config)
    }

    @Test
    fun initialize_refuses_to_be_re_pointed_at_a_different_config() {
        // Two configs in one process means two keys, and therefore two tenants or
        // two modes: whichever call ran last would silently decide whose data an
        // enrollment landed in.
        FaceCheck.initialize(FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL))

        val failure = assertFailsWith<FaceCheckException> {
            FaceCheck.initialize(FaceCheckConfig(apiKey = LIVE_KEY, baseUrl = BASE_URL))
        }
        assertEquals(FaceCheckErrorCode.INVALID_CONFIG, failure.code)
        assertEquals(FaceCheckMode.TEST, FaceCheck.config?.mode)
    }

    @Test
    fun building_a_machine_before_initialize_reports_NOT_INITIALIZED() {
        val failure = assertFailsWith<FaceCheckException> { FaceCheck.newChallengeMachine() }
        assertEquals(FaceCheckErrorCode.NOT_INITIALIZED, failure.code)
    }

    @Test
    fun the_machine_it_builds_matches_the_configured_challenge_count() {
        FaceCheck.initialize(
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL, challengeCount = 3),
        )
        assertEquals(3, FaceCheck.newChallengeMachine().challenges.size)
    }

    @Test
    fun shutdown_allows_a_fresh_configuration() {
        FaceCheck.initialize(FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL))
        FaceCheck.shutdown()
        assertFalse(FaceCheck.isInitialized)

        FaceCheck.initialize(FaceCheckConfig(apiKey = LIVE_KEY, baseUrl = BASE_URL))
        assertEquals(FaceCheckMode.LIVE, FaceCheck.config?.mode)
    }
}