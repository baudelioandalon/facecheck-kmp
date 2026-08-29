package com.borealnetwork.facecheck.model

import kotlinx.serialization.json.Json
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostedEnrollmentSessionTest {
    @Test
    fun decodesTheOwnerGeneratedSevenDayLinkContract() {
        val session = Json.decodeFromString<HostedEnrollmentSession>(
            """{
              "version":"1.0",
              "sessionId":"ws_12345678901234567890",
              "operation":"enroll",
              "subjectId":"shared_person_01",
              "mode":"test",
              "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=fcw_launch.ws_12345678901234567890.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "expiresAt":"2026-08-31T19:00:00.000Z"
            }""".trimIndent(),
        )

        assertEquals("shared_person_01", session.subjectId)
        assertEquals("test", session.mode)
        assertEquals(
            session.hostedUrl,
            session.shareUrlAt(Instant.parse("2026-08-27T19:00:00.000Z")),
        )
    }

    @Test
    fun rejectsAContractThatIsNotForEnrollment() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<HostedEnrollmentSession>(
                """{
                  "version":"1.0",
                  "sessionId":"ws_12345678901234567890",
                  "operation":"verify",
                  "subjectId":"shared_person_01",
                  "mode":"test",
                  "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=token",
                  "expiresAt":"2026-08-31T19:00:00.000Z"
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun rejectsAMalformedOneTimeCredential() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<HostedEnrollmentSession>(
                """{
                  "version":"1.0",
                  "sessionId":"ws_12345678901234567890",
                  "operation":"enroll",
                  "subjectId":"shared_person_01",
                  "mode":"test",
                  "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=fcw_launch.ws_12345678901234567890.short",
                  "expiresAt":"2026-08-31T19:00:00.000Z"
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun rejectsAMalformedExpiration() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<HostedEnrollmentSession>(
                """{
                  "version":"1.0",
                  "sessionId":"ws_12345678901234567890",
                  "operation":"enroll",
                  "subjectId":"shared_person_01",
                  "mode":"test",
                  "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=fcw_launch.ws_12345678901234567890.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "expiresAt":"not-a-date"
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun refusesToShareAtTheExpirationBoundary() {
        val session = Json.decodeFromString<HostedEnrollmentSession>(
            """{
              "version":"1.0",
              "sessionId":"ws_12345678901234567890",
              "operation":"enroll",
              "subjectId":"shared_person_01",
              "mode":"test",
              "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=fcw_launch.ws_12345678901234567890.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "expiresAt":"2026-08-31T19:00:00.000Z"
            }""".trimIndent(),
        )

        assertEquals(session.hostedUrl, session.shareUrlAt(Instant.parse("2026-08-31T18:59:59.999Z")))
        assertFailsWith<IllegalArgumentException> {
            session.shareUrlAt(Instant.parse(session.expiresAt))
        }
    }
}
