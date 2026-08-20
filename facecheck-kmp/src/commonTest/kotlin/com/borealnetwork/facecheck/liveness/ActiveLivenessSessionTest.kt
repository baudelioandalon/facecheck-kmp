package com.borealnetwork.facecheck.liveness

import com.borealnetwork.facecheck.FaceCheck
import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.camera.CameraController
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.LocationContext
import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.ModelProfileSummary
import com.borealnetwork.facecheck.model.SessionModelProfile
import com.borealnetwork.facecheck.model.VerifyResult
import com.borealnetwork.facecheck.net.FaceCheckBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TEST_KEY = "lk_test_a1b2c3d4e5f6g7h8"
private const val BASE_URL = "https://facecheck.example.com"

private val freshLocation = LocationContext(
    latitude = 19.4326,
    longitude = -99.1332,
    accuracyMeters = 22.0,
    capturedAt = Instant.parse("2026-08-14T12:00:00Z"),
)

private class FakeSessionBackend(
    private val challengePlan: List<ServerChallenge> =
        listOf(ServerChallenge.TURN_LEFT, ServerChallenge.TURN_RIGHT),
) : FaceCheckBackend {
    var requestedOperation: String? = null
    var requestedSubjectId: String? = null
    var requestedModelProfileId: String? = null
    var requestedLocation: LocationContext? = null
    var enrollCalls = 0
    var verifyCalls = 0

    override suspend fun enroll(
        subjectId: String,
        selfie: ByteArray,
        ine: ByteArray?,
        grant: String?,
        overwrite: Boolean,
    ): EnrollResult = error("legacy enroll should not be used by prepared sessions")

    override suspend fun verify(
        subjectId: String,
        selfie: ByteArray,
        compareWith: CompareWith,
    ): VerifyResult = error("legacy verify should not be used by prepared sessions")

    override suspend fun getEnrollmentModelProfiles(): ModelProfileCatalog =
        ModelProfileCatalog(
            defaultProfileId = "arcface-w600k-mbf-r1",
            profiles = listOf(
                ModelProfileSummary(
                    id = "arcface-w600k-mbf-r1",
                    rank = 1,
                    displayName = "ArcFace Mobile · Recomendado",
                    availability = "test",
                    badge = "Experimental",
                    recognizerArtifactBytes = 13_616_099,
                    totalArtifactBytes = 13_616_099,
                ),
            ),
        )

    override suspend fun createLivenessSession(
        operation: String,
        subjectId: String,
        requestedModelProfileId: String?,
        location: LocationContext,
    ): LivenessSessionDescriptor {
        requestedOperation = operation
        requestedSubjectId = subjectId
        this.requestedModelProfileId = requestedModelProfileId
        requestedLocation = location
        return LivenessSessionDescriptor(
            sessionId = "ls_abcdefghijklmnopqrst",
            subjectId = subjectId,
            operation = operation,
            expiresAt = Instant.parse("2026-08-14T12:02:00Z"),
            modelProfile = SessionModelProfile(
                id = requestedModelProfileId ?: "arcface-w600k-mbf-r1",
                displayName = "ArcFace Mobile · Recomendado",
                rank = 1,
            ),
            protocolVersion = "active-liveness-v1",
            challengePlan = challengePlan,
        )
    }

    override suspend fun enroll(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        grant: String?,
        overwrite: Boolean,
        ine: ByteArray?,
    ): EnrollResult {
        enrollCalls++
        assertEquals(EvidenceRole.entries.toList(), evidence.images.map { it.role })
        return EnrollResult(enrolled = true, subjectId = session.subjectId, mode = "test")
    }

    override suspend fun verify(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        compareWith: CompareWith,
    ): VerifyResult {
        verifyCalls++
        assertEquals(EvidenceRole.entries.toList(), evidence.images.map { it.role })
        return VerifyResult(verified = true, verificationId = "vrf_123")
    }

    override fun close() = Unit
}

private class PreparedScriptedCamera(
    private val script: List<FaceFrame>,
    private val still: CapturedJpeg = CapturedJpeg("jpeg".encodeToByteArray(), width = 32, height = 24),
) : CameraController {
    var captures = 0

    override val frames: Flow<FaceFrame>
        get() = flow {
            script.forEach { frame ->
                emit(frame)
                yield()
            }
        }

    override suspend fun captureStill(): CapturedJpeg {
        captures++
        return still
    }

    override fun start() = Unit

    override fun stop() = Unit
}

class ActiveLivenessSessionTest {

    @AfterTest
    fun tearDown() = FaceCheck.shutdown()

    @Test
    fun enrollment_preparation_sends_profile_and_location_then_uses_returned_plan() = runTest {
        val backend = FakeSessionBackend(
            challengePlan = listOf(ServerChallenge.TURN_RIGHT, ServerChallenge.TURN_LEFT),
        )
        FaceCheck.initializeForTests(
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL),
            backend,
        )

        val session = FaceCheck.prepareEnrollment(
            subjectId = "person_demo_01",
            modelProfileId = "arcface-w600k-mbf-r1",
            location = freshLocation,
        )

        assertEquals("enroll", backend.requestedOperation)
        assertEquals("person_demo_01", backend.requestedSubjectId)
        assertEquals("arcface-w600k-mbf-r1", backend.requestedModelProfileId)
        assertEquals(freshLocation, backend.requestedLocation)
        assertEquals(listOf(ServerChallenge.TURN_RIGHT, ServerChallenge.TURN_LEFT), session.challengePlan)
        assertEquals("arcface-w600k-mbf-r1", session.modelProfile.id)
    }

    @Test
    fun a_prepared_session_runs_once_and_consumes_canonical_evidence() = runTest {
        val backend = FakeSessionBackend()
        FaceCheck.initializeForTests(
            FaceCheckConfig(apiKey = TEST_KEY, baseUrl = BASE_URL),
            backend,
        )
        val session = FaceCheck.prepareEnrollment(
            subjectId = "person_demo_01",
            modelProfileId = "arcface-w600k-mbf-r1",
            location = freshLocation,
        )

        val result = session.run(camera = scriptedCamera())

        assertTrue(result.enrolled)
        assertEquals(1, backend.enrollCalls)
        assertEquals(ActiveLivenessState.Completed, session.state.value)

        val error = assertFailsWith<FaceCheckException> {
            session.run(camera = scriptedCamera())
        }
        assertEquals(FaceCheckErrorCode.LIVENESS_SESSION_CONSUMED, error.code)
    }
}

private fun scriptedCamera(): PreparedScriptedCamera =
    PreparedScriptedCamera(
        script = listOf(
            frame(0),
            frame(testConfig.positioningHoldMs + 100),
            frame(testConfig.positioningHoldMs + 200, yaw = -40f),
            frame(testConfig.positioningHoldMs + 300),
            frame(testConfig.positioningHoldMs + 400, yaw = 40f),
            frame(testConfig.positioningHoldMs + 500),
        ),
    )
