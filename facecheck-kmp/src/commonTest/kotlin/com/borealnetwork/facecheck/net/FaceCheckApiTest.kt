package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.FaceCheckLogLevel
import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.FaceCheckLogSink
import com.borealnetwork.facecheck.SubjectId
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.LocationContext
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
import kotlinx.datetime.Instant
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
private val FRONT_INITIAL = "front-initial-jpeg".encodeToByteArray()
private val TURN_FIRST = "turn-first-jpeg".encodeToByteArray()
private val CENTER_BETWEEN = "center-between-jpeg".encodeToByteArray()
private val TURN_SECOND = "turn-second-jpeg".encodeToByteArray()
private val FRONT_FINAL = "front-final-jpeg".encodeToByteArray()

private val FRESH_LOCATION = LocationContext(
    latitude = 19.4326,
    longitude = -99.1332,
    accuracyMeters = 35.0,
    capturedAt = Instant.parse("2026-08-14T12:00:00Z"),
)

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
  "similarity": 0.61,
  "ineSimilarity": 0.77,
  "spoofScore": null,
  "faceQuality": null,
  "verificationId": "vrf_123"
}
"""

private const val MODEL_PROFILES_BODY = """
{
  "defaultProfileId": "arcface-w600k-mbf-r1",
  "profiles": [
    {
      "id": "arcface-w600k-mbf-r1",
      "rank": 1,
      "displayName": "ArcFace Mobile · Recomendado",
      "availability": "test",
      "badge": "Experimental",
      "artifactBytes": 13616099,
      "passivePadArtifactBytes": null,
      "totalArtifactBytes": 13616099
    }
  ]
}
"""

private const val LIVENESS_SESSION_BODY = """
{
  "sessionId": "ls_abcdefghijklmnopqrst",
  "expiresAt": "2026-08-14T12:02:00Z",
  "modelProfile": {
    "id": "arcface-w600k-mbf-r1",
    "displayName": "ArcFace Mobile · Recomendado",
    "rank": 1
  },
  "protocolVersion": "active-liveness-v1",
  "challengePlan": ["turn_left", "turn_right"],
  "capturePolicy": {
    "visibleSteps": 3,
    "maxEvidenceImages": 5,
    "sessionTimeoutSeconds": 120
  }
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

        val result = api.enroll(subjectId = "person_demo_01", selfie = SELFIE)

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

        val result = api.verify("person_demo_01", SELFIE, CompareWith.BOTH)

        assertFalse(result.verified)
        assertEquals("FACE_MISMATCH", result.reason)
        assertEquals(false, result.checks.faceMatch)
        assertEquals(true, result.checks.ineMatch)
        assertEquals(CompareWith.BOTH, result.compareWith)
        assertEquals(0.61, result.similarity)
        assertEquals(0.77, result.ineSimilarity)
        assertEquals("vrf_123", result.verificationId)
        assertEquals("El rostro no coincide con el registrado.", result.messageEs)
    }

    @Test
    fun an_unknown_field_in_the_response_does_not_break_a_shipped_app() = runTest {
        // There is no update path for an SDK already on someone's phone.
        val api = api {
            respondJson("""{"enrolled":true,"subjectId":"s1","brandNewField":42}""")
        }

        assertTrue(api.enroll("person_demo_01", SELFIE).enrolled)
    }

    // --- The request on the wire ----------------------------------------------

    @Test
    fun every_request_carries_the_api_key_header_and_the_right_url() = runTest {
        var seen: HttpRequestData? = null
        val api = api {
            seen = it
            respondJson(ENROLL_BODY)
        }

        api.enroll("person_demo_01", SELFIE)

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
    fun enroll_sends_subject_id_not_email_and_the_files_the_backend_requires() = runTest {
        var body = ""
        val api = api {
            body = it.body.readAsText()
            respondJson(ENROLL_BODY)
        }

        api.enroll("person_demo_01", SELFIE, ine = INE, overwrite = true)

        assertContains(body, "subjectId")
        assertContains(body, "person_demo_01")
        assertFalse(body.contains("name=\"email\""), "legacy email part: $body")
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

        api.enroll("person_demo_01", SELFIE)

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

        api.verify("person_demo_01", SELFIE, CompareWith.BOTH)

        assertContains(body, "compareWith")
        assertContains(body, "both")
    }

    @Test
    fun catalog_parses_exact_backend_artifact_bytes() = runTest {
        var seen: HttpRequestData? = null
        val api = api {
            seen = it
            respondJson(MODEL_PROFILES_BODY)
        }

        val catalog = api.getEnrollmentModelProfiles()

        assertEquals(HttpMethod.Get, checkNotNull(seen).method)
        assertEquals("$BASE_URL/modelProfiles?operation=enroll", checkNotNull(seen).url.toString())
        assertEquals(TEST_KEY, checkNotNull(seen).headers["X-Api-Key"])
        assertEquals("arcface-w600k-mbf-r1", catalog.defaultProfileId)
        assertEquals(13_616_099L, catalog.profiles.single().recognizerArtifactBytes)
        assertEquals(13_616_099L, catalog.profiles.single().totalArtifactBytes)
    }

    @Test
    fun liveness_session_creation_sends_location_profile_and_subject() = runTest {
        var request: HttpRequestData? = null
        var body = ""
        val api = api {
            request = it
            body = it.body.readAsText()
            respondJson(LIVENESS_SESSION_BODY)
        }

        val descriptor = api.createLivenessSession(
            operation = "enroll",
            subjectId = "person_demo_01",
            requestedModelProfileId = "arcface-w600k-mbf-r1",
            location = FRESH_LOCATION,
        )

        assertEquals(HttpMethod.Post, checkNotNull(request).method)
        assertEquals("$BASE_URL/livenessSessions", checkNotNull(request).url.toString())
        assertEquals(TEST_KEY, checkNotNull(request).headers["X-Api-Key"])
        assertContains(checkNotNull(request).body.contentType.toString(), "application/json")
        assertContains(body, """"operation":"enroll"""")
        assertContains(body, """"subjectId":"person_demo_01"""")
        assertContains(body, """"sdk":{"platform":"kmp","version":"1.0.0"}""")
        assertContains(body, """"requestedModelProfileId":"arcface-w600k-mbf-r1"""")
        assertContains(body, """"latitude":19.4326""")
        assertEquals("ls_abcdefghijklmnopqrst", descriptor.sessionId)
        assertEquals(listOf("turn_left", "turn_right"), descriptor.challengePlan.map { it.wire })
        assertEquals(3, descriptor.capturePolicy.visibleSteps)
    }

    @Test
    fun a_liveness_session_rejection_logs_only_the_structured_failure_envelope() = runTest {
        val previousLevel = FaceCheckLogger.level
        val previousSink = FaceCheckLogger.sink
        val warningLines = mutableListOf<String>()
        FaceCheckLogger.level = FaceCheckLogLevel.WARN
        FaceCheckLogger.sink = FaceCheckLogSink { _, message -> warningLines += message }

        try {
            val api = api {
                respondJson(
                    """{"error":{"code":"LOCATION_REQUIRED","message":"sensitive session diagnostic"}}""",
                    HttpStatusCode.BadRequest,
                )
            }

            assertFailsWith<FaceCheckException> {
                api.createLivenessSession(
                    operation = "enroll",
                    subjectId = "person_demo_01",
                    requestedModelProfileId = "arcface-w600k-mbf-r1",
                    location = FRESH_LOCATION,
                )
            }

            assertEquals(
                listOf("operation=liveness_session code=LOCATION_REQUIRED httpStatus=400 retryable=false"),
                warningLines,
            )
            assertFalse(warningLines.joinToString().contains("sensitive session diagnostic"))
        } finally {
            FaceCheckLogger.level = previousLevel
            FaceCheckLogger.sink = previousSink
        }
    }

    @Test
    fun enroll_sends_session_profile_manifest_and_five_evidence_files() = runTest {
        var body = ""
        val api = api {
            body = it.body.readAsText()
            respondJson("""{"enrolled":true,"subjectId":"test_9f86d081"}""")
        }

        api.enroll(
            session = livenessDescriptor(),
            evidence = evidenceBundle(),
            grant = null,
            overwrite = false,
            ine = null,
        )

        assertContains(body, "livenessSessionId")
        assertContains(body, "ls_abcdefghijklmnopqrst")
        assertContains(body, "modelProfileId")
        assertContains(body, "arcface-w600k-mbf-r1")
        listOf("front_initial", "turn_first", "center_between", "turn_second", "front_final")
            .forEachIndexed { index, role ->
                assertContains(body, "name=\"evidence_$index\"")
                assertContains(body, "filename=\"evidence_$index.jpg\"")
                assertContains(body, role)
            }
        assertFalse(body.contains("filename=\"selfie.jpg\""), "legacy selfie part: $body")
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
            api.verify("person_demo_unknown", SELFIE)
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
            api.verify("person_demo_01", SELFIE)
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
            api.enroll("person_demo_01", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.UNKNOWN, failure.code)
        assertEquals("Algo pasó.", failure.message)
    }

    @Test
    fun an_unparseable_success_body_is_reported_as_an_invalid_response() = runTest {
        val api = api { respondJson("not json at all") }

        val failure = assertFailsWith<FaceCheckException> {
            api.enroll("person_demo_01", SELFIE)
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
            api.enroll("person_demo_01", SELFIE, overwrite = true)
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

        assertTrue(api.enroll("person_demo_01", SELFIE).enrolled)
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
            api.enroll("person_demo_01", SELFIE)
        }

        assertEquals(FaceCheckErrorCode.KEY_SERVICE_UNAVAILABLE, failure.code)
        assertTrue(failure.isRetryable)
        assertEquals(3, attempts, "expected the original attempt plus two retries")
    }

    @Test
    fun a_retriable_failure_logs_only_the_structured_failure_envelope() = runTest {
        val previousLevel = FaceCheckLogger.level
        val previousSink = FaceCheckLogger.sink
        val warningLines = mutableListOf<String>()
        FaceCheckLogger.level = FaceCheckLogLevel.WARN
        FaceCheckLogger.sink = FaceCheckLogSink { _, message -> warningLines += message }

        try {
            var attempts = 0
            val api = api(maxRetries = 1) {
                attempts++
                respondJson(
                    """{"error":{"code":"INTERNAL","message":"sensitive diagnostic payload"}}""",
                    HttpStatusCode.InternalServerError,
                )
            }

            assertFailsWith<FaceCheckException> { api.enroll("person_demo_01", SELFIE) }

            assertEquals(2, attempts)
            assertEquals(
                listOf(
                    "operation=enroll code=INTERNAL httpStatus=500 retryable=true",
                    "operation=enroll code=INTERNAL httpStatus=500 retryable=true",
                ),
                warningLines,
            )
            assertFalse(warningLines.joinToString().contains("sensitive diagnostic payload"))
        } finally {
            FaceCheckLogger.level = previousLevel
            FaceCheckLogger.sink = previousSink
        }
    }

    @Test
    fun a_transport_failure_is_retried_and_then_reported_as_a_network_error() = runTest {
        var attempts = 0
        val api = api(maxRetries = 1) {
            attempts++
            throw kotlinx.io.IOException("connection reset")
        }

        val failure = assertFailsWith<FaceCheckException> {
            api.verify("person_demo_01", SELFIE)
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

        assertFailsWith<FaceCheckException> { api.enroll("person_demo_01", SELFIE) }
        assertEquals(1, attempts)
    }
}

private fun livenessDescriptor() = com.borealnetwork.facecheck.liveness.LivenessSessionDescriptor(
    sessionId = "ls_abcdefghijklmnopqrst",
    subjectId = "person_demo_01",
    operation = "enroll",
    expiresAt = Instant.parse("2026-08-14T12:02:00Z"),
    modelProfile = com.borealnetwork.facecheck.model.SessionModelProfile(
        id = "arcface-w600k-mbf-r1",
        displayName = "ArcFace Mobile · Recomendado",
        rank = 1,
    ),
    protocolVersion = "active-liveness-v1",
    challengePlan = listOf(
        com.borealnetwork.facecheck.liveness.ServerChallenge.TURN_LEFT,
        com.borealnetwork.facecheck.liveness.ServerChallenge.TURN_RIGHT,
    ),
    capturePolicy = com.borealnetwork.facecheck.liveness.CapturePolicy(
        visibleSteps = 3,
        maxEvidenceImages = 5,
        sessionTimeoutSeconds = 120,
    ),
)

private fun evidenceBundle() = com.borealnetwork.facecheck.liveness.CapturedEvidenceBundle(
    images = listOf(
        com.borealnetwork.facecheck.liveness.CapturedEvidence(
            com.borealnetwork.facecheck.liveness.EvidenceRole.FRONT_INITIAL,
            FRONT_INITIAL,
        ),
        com.borealnetwork.facecheck.liveness.CapturedEvidence(
            com.borealnetwork.facecheck.liveness.EvidenceRole.TURN_FIRST,
            TURN_FIRST,
        ),
        com.borealnetwork.facecheck.liveness.CapturedEvidence(
            com.borealnetwork.facecheck.liveness.EvidenceRole.CENTER_BETWEEN,
            CENTER_BETWEEN,
        ),
        com.borealnetwork.facecheck.liveness.CapturedEvidence(
            com.borealnetwork.facecheck.liveness.EvidenceRole.TURN_SECOND,
            TURN_SECOND,
        ),
        com.borealnetwork.facecheck.liveness.CapturedEvidence(
            com.borealnetwork.facecheck.liveness.EvidenceRole.FRONT_FINAL,
            FRONT_FINAL,
        ),
    ),
)

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
