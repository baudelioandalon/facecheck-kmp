package com.borealnetwork.facecheck.liveness

import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.camera.CameraController
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.SessionModelProfile
import com.borealnetwork.facecheck.model.VerifyResult
import com.borealnetwork.facecheck.net.FaceCheckBackend
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class ServerChallenge(val wire: String) {
    TURN_LEFT("turn_left"),
    TURN_RIGHT("turn_right"),
    ;

    val challenge: Challenge
        get() = when (this) {
            TURN_LEFT -> Challenge.TurnLeft
            TURN_RIGHT -> Challenge.TurnRight
        }

    companion object {
        fun fromWire(wire: String): ServerChallenge? =
            entries.firstOrNull { it.wire.equals(wire.trim(), ignoreCase = true) }
    }
}

@Serializable
internal data class LivenessSessionWire(
    val sessionId: String,
    val expiresAt: Instant,
    val modelProfile: SessionModelProfile,
    val protocolVersion: String,
    val challengePlan: List<String>,
    val capturePolicy: CapturePolicy = CapturePolicy(),
)

data class LivenessSessionDescriptor(
    val sessionId: String,
    val subjectId: String,
    val operation: String,
    val expiresAt: Instant,
    val modelProfile: SessionModelProfile,
    val protocolVersion: String,
    val challengePlan: List<ServerChallenge>,
    val capturePolicy: CapturePolicy = CapturePolicy(),
) {
    val modelProfileId: String get() = modelProfile.id
}

@Serializable
data class CapturePolicy(
    val visibleSteps: Int = 3,
    val maxEvidenceImages: Int = EvidenceRole.entries.size,
    val sessionTimeoutSeconds: Int = 120,
)

sealed interface ActiveLivenessState {
    data object Ready : ActiveLivenessState
    data class Capturing(val presentation: LivenessState) : ActiveLivenessState
    data object Uploading : ActiveLivenessState
    data object Processing : ActiveLivenessState
    data object Completed : ActiveLivenessState
    data class Failed(val error: FaceCheckException) : ActiveLivenessState
    data object Cancelled : ActiveLivenessState
}

abstract class PreparedLivenessSession internal constructor(
    val descriptor: LivenessSessionDescriptor,
    private val backend: FaceCheckBackend,
    private val config: FaceCheckConfig,
) {
    private val _state = MutableStateFlow<ActiveLivenessState>(ActiveLivenessState.Ready)
    private var consumed = false

    val state: StateFlow<ActiveLivenessState> = _state.asStateFlow()
    val sessionId: String get() = descriptor.sessionId
    val subjectId: String get() = descriptor.subjectId
    val expiresAt: Instant get() = descriptor.expiresAt
    val modelProfile: SessionModelProfile get() = descriptor.modelProfile
    val challengePlan: List<ServerChallenge> get() = descriptor.challengePlan
    val capturePolicy: CapturePolicy get() = descriptor.capturePolicy

    protected suspend fun capture(camera: CameraController): CapturedEvidenceBundle {
        if (consumed) {
            val error = FaceCheckException(
                code = FaceCheckErrorCode.LIVENESS_SESSION_CONSUMED,
                message = "Esta sesión de liveness ya fue usada. Inicia un nuevo intento.",
            )
            _state.value = ActiveLivenessState.Failed(error)
            throw error
        }
        consumed = true

        val machine = ChallengeMachine.serverDriven(
            serverChallenges = descriptor.challengePlan,
            config = config.liveness,
        )
        return try {
            coroutineScope {
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    machine.state.collect { presentation ->
                        _state.value = ActiveLivenessState.Capturing(presentation)
                    }
                }
                val capture = try {
                    runLivenessSession(camera, machine, config.livenessTimeoutMs)
                } finally {
                    collector.cancelAndJoin()
                }
                _state.value = ActiveLivenessState.Uploading
                capture.evidenceBundle
            }
        } catch (failure: FaceCheckException) {
            _state.value = if (failure.code == FaceCheckErrorCode.CANCELLED) {
                ActiveLivenessState.Cancelled
            } else {
                ActiveLivenessState.Failed(failure)
            }
            throw failure
        }
    }

    private var pendingEvidence: CapturedEvidenceBundle? = null

    protected suspend fun captureAndStore(camera: CameraController): CapturedEvidenceBundle =
        capture(camera).also { pendingEvidence = it }

    protected fun <T> markCompleted(result: T): T {
        _state.value = ActiveLivenessState.Completed
        return result
    }

    protected fun markFailed(failure: FaceCheckException): Nothing {
        _state.value = ActiveLivenessState.Failed(failure)
        throw failure
    }
}

class EnrollmentSession internal constructor(
    descriptor: LivenessSessionDescriptor,
    private val backend: FaceCheckBackend,
    config: FaceCheckConfig,
) : PreparedLivenessSession(descriptor, backend, config) {
    suspend fun run(
        camera: CameraController,
        grant: String? = null,
        overwrite: Boolean = false,
        ine: ByteArray? = null,
    ): EnrollResult {
        val evidence = captureAndStore(camera)
        return try {
            val result = backend.enroll(
                session = descriptor,
                evidence = evidence,
                grant = grant,
                overwrite = overwrite,
                ine = ine,
            )
            markCompleted(result)
        } catch (failure: FaceCheckException) {
            markFailed(failure)
        }
    }
}

class VerificationSession internal constructor(
    descriptor: LivenessSessionDescriptor,
    private val backend: FaceCheckBackend,
    config: FaceCheckConfig,
) : PreparedLivenessSession(descriptor, backend, config) {
    suspend fun run(
        camera: CameraController,
        compareWith: CompareWith = CompareWith.ENROLLMENT,
    ): VerifyResult {
        val evidence = captureAndStore(camera)
        return try {
            val result = backend.verify(
                session = descriptor,
                evidence = evidence,
                compareWith = compareWith,
            )
            markCompleted(result)
        } catch (failure: FaceCheckException) {
            markFailed(failure)
        }
    }
}
