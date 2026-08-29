package com.borealnetwork.facecheck.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MobileCompanyContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun companyIdentityIsAdditiveAndNullableInSafeResults() {
        val omitted = json.decodeFromString<EnrollResult>(
            """{"enrolled":true,"subjectId":"shared_person_01"}""",
        )
        val explicitNull = json.decodeFromString<VerifyResult>(
            """{"verified":true,"companyId":null}""",
        )
        val valid = json.decodeFromString<EnrollResult>(
            """{"enrolled":true,"subjectId":"shared_person_01","companyId":"cmp_AAAAAAAAAAAAAAAAAAAAAA"}""",
        )

        assertNull(omitted.companyId)
        assertNull(explicitNull.companyId)
        assertEquals("cmp_AAAAAAAAAAAAAAAAAAAAAA", valid.companyId)
    }

    @Test
    fun hostedEnrollmentCarriesTheSameOptionalServerOwnedCompany() {
        val session = json.decodeFromString<HostedEnrollmentSession>(
            """{
              "version":"1.0",
              "sessionId":"ws_12345678901234567890",
              "operation":"enroll",
              "subjectId":"shared_person_01",
              "companyId":"cmp_AAAAAAAAAAAAAAAAAAAAAA",
              "mode":"test",
              "hostedUrl":"https://capture.borealnetwork.org/session/ws_12345678901234567890#token=fcw_launch.ws_12345678901234567890.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "expiresAt":"2026-08-31T19:00:00.000Z"
            }""".trimIndent(),
        )

        assertEquals("cmp_AAAAAAAAAAAAAAAAAAAAAA", session.companyId)
    }

    @Test
    fun malformedNonNullCompanyIdentityRejectsTheResponse() {
        for (payload in listOf(
            """{"enrolled":true,"subjectId":"shared_person_01","companyId":"editable-company"}""",
            """{"verified":true,"companyId":"cmp_short"}""",
        )) {
            assertFails {
                if (payload.contains("enrolled")) json.decodeFromString<EnrollResult>(payload)
                else json.decodeFromString<VerifyResult>(payload)
            }
        }
    }

    @Test
    fun mapsEveryCompanyLifecycleFailureAsNonRetryable() {
        val expected = setOf(
            "COMPANY_NOT_ASSIGNED",
            "COMPANY_NOT_FOUND",
            "COMPANY_ACCESS_DENIED",
            "COMPANY_MIGRATION_IN_PROGRESS",
            "COMPANY_SERVICE_ENDED",
            "COMPANY_DELETE_NOT_APPROVED",
            "COMPANY_DELETING",
            "COMPANY_DELETION_FAILED",
        )

        for (wire in expected) {
            val code = FaceCheckErrorCode.fromWire(wire)
            assertEquals(wire, code.wire)
            assertFalse(code.isRetryable)
        }
    }
}
