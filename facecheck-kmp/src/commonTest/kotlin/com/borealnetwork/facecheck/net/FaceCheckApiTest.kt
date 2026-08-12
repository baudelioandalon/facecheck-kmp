package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_KEY = "lk_test_a1b2c3d4e5f6g7h8"
private const val BASE_URL = "https://facecheck.example.com"

private val SELFIE = "fake-jpeg-selfie".encodeToByteArray()
private val INE = "fake-jpeg-ine".encodeToByteArray()

private const val ENROLL_BODY = """
{
  "enrolled": true,
  "overwritten": false,
  "subjectId": "test_9f86d081",
  "mode": "test",
  "spoofScore": null,
  "faceQuality": {
    "imageWidth": 1080, "imageHeight": 1440,
    "faceWidth": 430, "faceHeight": 520,
    "faceRatio": 0.398, "detectorScore": 0.999,
    "sharpness": 180.5, "brightness": 131.2, "aligned": true
  },
  "ineEnrolled": false,
  "ineQuality": null,
  "modelVersion": "w600k_mbf-1"
}
"""

private const val VERIFY_BODY = """
{
  "verified": false,
  "reason": "FACE_MISMATCH",
  "message": "El rostro no coincide con el registrado.",
  "compareWith": "both",
  "checks": {
    "faceMatch": false, "ineMatch": true,
    "liveness": null, "livenessEnforced": false
  },
  "spoofScore": null,
  "faceQuality": null,
  "verificationId": "vrf_123"
}
"""

class FaceCheckApiTest {

    private fun config(maxRetries: Int = 2) = FaceCheckConfig(
        apiKey = TEST_KEY,
        baseUrl = BASE_URL,
        maxRetries = maxRetries,
    )

    /** An API whose retries never actually sleep and whose engine is scripted. */
    private fun api(maxRetries: Int = 2, handler: MockRequestHandler) = FaceCheckApi(
        config = config(maxRetries),
        engine = MockEngine(handler),
        random = Random(0),
        sleep = { /* tests do not wait out a backoff */ },
    )

    // --- Happy paths ----------------------------------------------------------

    @Test
    fun enroll_parses_the_success_envelope() = runTest {
        val api = api { respondJson(ENROLL_BODY) }

        val result = api.enroll(email = "persona@ejemplo.com", selfie = SELFIE)

        assertTrue(result.enrolled)
        assertEquals("test_9f86d081", result.subjectId)
        assertFalse(result.overwritten)
        assertEquals(0.398f, result.faceQuality?.faceRatio)
        assertEquals("w600k_mbf-1", result.modelVersion)
        assertNull(result.spoofScore)
    }

    @Test
    fun verify_parses_a_negative_verdict_as_a_result_not_an_error() = runTest {
        // A face that does not match is an answer, not a failure. Throwing here
        // would force every integrator into a try/catch for the normal case.
        val api = api { respondJson(VERIFY_BODY) }

        val result = api.verify("persona@ejemplo.com", SELFIE, CompareWith.BOTH)

        assertFalse(result.verified)
        assertEquals("FACE_MISMATCH", result.reason)
        assertEquals(false, result.checks.faceMatch)
        assertEquals(true, result.checks.ineMatch)
        assertEquals(CompareWith.BOTH, result.compareWith)
        assertEquals("El rostro no coincide con el registrado.", result.messageEs)
    }

    @Test
    fun an_unknown_field_in_the_response_does_not_break_a_shipped_app() = runTest {
        // There is no update path for an SDK already on someone's phone.
        val api = api {
            respondJson("""{"enrolled":true,"subjectId":"s1","brandNewField":42}""")
        }

        assertTrue(api.enroll("persona@ejemplo.com", SELFIE).enrolled)
    }

    // --- The request on the wire ----------------------------------------------

    @Test
    fun every_request_carries_the_api_key_header_and_the_right_url() = runTest {
        var seen: HttpRequestData? = null
        val api = api {
            seen = it
            respondJson(ENROLL_BODY)
        }

        api.enroll("persona@ejemplo.com", SELFIE)

        val request = checkNotNull(seen)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$BASE_URL/enroll", request.url.toString())
        assertEquals(TEST_KEY, request.headers["X-Api-Key"])
        assertContains(
            request.body.contentType.toString(),
            "multipart/form-data",
        )
    }

    @Test
    fun enroll_sends_the_fields_and_files_the_backend_requires() = runTest {
        var body = ""
        val api = api {
            body = it.body.readAsText()
            respondJson(ENROLL_BODY)
        }

        api.enroll("Persona@Ejemplo.com", SELFIE, ine = INE, overwrite = true)

        // Sent exactly as given: normalising an email is the backend's job, and
        // doing it in two places is how the two stop agreeing.
        assertContains(body, "Persona@Ejemplo.com")
        assertContains(body, "overwrite")
        assertContains(body, "true")
        // Both the filename and the content type are mandatory: the backend
        // treats a part with neither as absent and answers MISSING_FILE with a
        // perfectly valid image sitting in the body.
        assertContains(body, "filename=\"selfie.jpg\"")
        assertContains(body, "filename=\"ine.jpg\"")
        assertContains(body, "image/jpeg")
        assertContains(body, SELFIE.decodeToString())
        assertContains(body, INE.decodeToString())
    }

    @Test
    fun enroll_omits_the_overwrite_flag_unless_it_was_asked_for() = runTest {
        var body = ""
        val api = api {
            body = it.body.readAsText()
            respondJson(ENROLL_BODY)
        }

        api.enroll("persona@ejemplo.com", SELFIE)

        assertFalse(body.contains("overwrite"), "unexpected overwrite part: $body")
        assertFalse(body.contains("filename=\"ine.jpg\""), "unexpected ine part: $body")
    }

    @Test
    fun verify_sends_the_requested_comparison() = runTest {
        var body = ""
        val api = api {
            body = it.body.readAsText()
            respondJson(VERIFY_BODY)
        }

        api.verify("persona@ejemplo.com", SELFIE, CompareWith.BOTH)

        assertContains(body, "compareWith")
        assertContains(body, "both")
    }

    // --- Errors ---------------------------------------------------------------

    @Test
    fun the_error_envelope_becomes_a_typed_exception() = runTest {
        val api = api {
            respondJson(
                """{"error":{"code":"NOT_ENROLLED","message":"Este correo no tiene un registro facial.","details":{"mode":"test"}}}""",
                HttpStatusCode.NotFound,
            )
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.verify("desconocido@ejemplo.com", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.NOT_ENROLLED, failure.code)
        assertEquals(404, failure.httpStatus)
        assertEquals("Este correo no tiene un registro facial.", failure.message)
        assertEquals("test", failure.details["mode"])
        assertFalse(failure.isRetryable)
    }

    @Test
    fun a_rate_limit_exposes_its_retry_hint() = runTest {
        val api = api {
            respondJson(
                """{"error":{"code":"RATE_LIMITED","message":"Demasiados intentos.","details":{"retryAfterSeconds":42,"lockedUntil":"2026-08-10T18:30:00Z"}}}""",
                HttpStatusCode.TooManyRequests,
            )
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.verify("persona@ejemplo.com", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.RATE_LIMITED, failure.code)
        assertEquals(42, failure.retryAfterSeconds)
        assertEquals("2026-08-10T18:30:00Z", failure.lockedUntil?.toString())
    }

    @Test
    fun an_unrecognised_code_degrades_instead_of_crashing() = runTest {
        val api = api {
            respondJson(
                """{"error":{"code":"SOMETHING_ADDED_LATER","message":"Algo pasó."}}""",
                HttpStatusCode.BadRequest,
            )
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.enroll("persona@ejemplo.com", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.UNKNOWN, failure.code)
        assertEquals("Algo pasó.", failure.message)
    }

    @Test
    fun an_unparseable_success_body_is_reported_as_an_invalid_response() = runTest {
        val api = api { respondJson("not json at all") }

        val failure = assertFailsWith<FaceCheckException> {
            api.enroll("persona@ejemplo.com", SELFIE)
        }
        assertEquals(FaceCheckErrorCode.INVALID_RESPONSE, failure.code)
    }

    // --- Retries --------------------------------------------------------------

    @Test
    fun a_4xx_is_never_retried() = runTest {
        // /verify bills the tenant and counts against the subject's lockout streak
        // on every attempt: retrying a 403 would spend three of a user's five
        // allowed failures on one tap and lock them out of their own account.
        var attempts = 0
        val api = api {
            attempts++
            respondJson(
                """{"error":{"code":"REENROLLMENT_FACE_MISMATCH","message":"No coincide."}}""",
                HttpStatusCode.Forbidden,
            )
        }

        assertFailsWith<FaceCheckException> {
            api.enroll("persona@ejemplo.com", SELFIE, overwrite = true)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun a_5xx_is_retried_up_to_the_configured_limit_and_then_succeeds() = runTest {
        var attempts = 0
        val api = api {
            attempts++
            if (attempts <= 2) {
                respondJson("""{"error":{"code":"INTERNAL","message":"boom"}}""", HttpStatusCode.InternalServerError)
            } else {
                respondJson(ENROLL_BODY)
            }
        }

        assertTrue(api.enroll("persona@ejemplo.com", SELFIE).enrolled)
        assertEquals(3, attempts)
    }

    @Test
    fun a_5xx_that_never_clears_surfaces_the_backend_error() = runTest {
        var attempts = 0
        val api = api {
            attempts++
            respondJson(
                """{"error":{"code":"KEY_SERVICE_UNAVAILABLE","message":"No disponible."}}""",
                HttpStatusCode.ServiceUnavailable,
            )
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.enroll("persona@ejemplo.com", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.KEY_SERVICE_UNAVAILABLE, failure.code)
        assertTrue(failure.isRetryable)
        assertEquals(3, attempts, "expected the original attempt plus two retries")
    }

    @Test
    fun a_transport_failure_is_retried_and_then_reported_as_a_network_error() = runTest {
        var attempts = 0
        val api = api(maxRetries = 1) {
            attempts++
            throw kotlinx.io.IOException("connection reset")
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.verify("persona@ejemplo.com", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.NETWORK_ERROR, failure.code)
        assertEquals(2, attempts)
    }

    @Test
    fun retries_can_be_switched_off_entirely() = runTest {
        var attempts = 0
        val api = api(maxRetries = 0) {
            attempts++
            respondJson("""{"error":{"code":"INTERNAL","message":"boom"}}""", HttpStatusCode.InternalServerError)
        }

        assertFailsWith<FaceCheckException> { api.enroll("persona@ejemplo.com", SELFIE) }
        assertEquals(1, attempts)
    }
}

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

/**
 * Read an outgoing multipart body back out, so a test can assert on what
 * actually went on the wire rather than on what the builder was handed.
 */
private suspend fun OutgoingContent.readAsText(): String = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    is OutgoingContent.WriteChannelContent -> coroutineScope {
        val channel = ByteChannel(autoFlush = true)
        launch {
            try {
                writeTo(channel)
            } finally {
                channel.flushAndClose()
            }
        }
        channel.readRemaining().readByteArray().decodeToString()
    }
    else -> ""
}