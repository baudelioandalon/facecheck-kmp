package com.borealnetwork.facecheck.liveness

import com.borealnetwork.facecheck.model.SessionModelProfile
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
