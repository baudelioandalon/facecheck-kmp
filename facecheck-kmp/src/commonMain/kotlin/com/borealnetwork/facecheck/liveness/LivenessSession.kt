package com.borealnetwork.facecheck.liveness

import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.camera.CameraController
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A completed liveness session: the photo to upload, plus what was seen. */
internal data class LivenessCapture(
    /** JPEG bytes from [CameraController.captureStill]. */
    val still: CapturedJpeg,
    val evidence: LivenessEvidence,
    val evidenceBundle: CapturedEvidenceBundle = CapturedEvidenceBundle(
        EvidenceRole.entries.map { role -> CapturedEvidence(role, still) },
    ),
) {
    // Generated equals/hashCode compare ByteArray by identity, which makes two
    // captures of the same photo unequal. Content comparison is what a caller
    // would expect, so it is spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LivenessCapture) return false
        return still == other.still &&
            evidence == other.evidence &&
            evidenceBundle == other.evidenceBundle
    }

    override fun hashCode(): Int = 31 * (31 * still.hashCode() + evidence.hashCode()) +
        evidenceBundle.hashCode()
}

/**
 * Runs one liveness session end to end: pump frames into [machine], take the
 * still when it asks for one, and stop the camera no matter how it ends.
 *
 * The camera is released in a `finally` at every level. A verification that
 * throws must not leave the camera light on — users read that as the app
 * spying on them, and they are not entirely wrong to.
 */
internal suspend fun runLivenessSession(
    camera: CameraController,
    machine: ChallengeMachine,
    timeoutMs: Long,
): LivenessCapture {
    camera.start()
    try {
        return withTimeout(timeoutMs) {
            coroutineScope {
                val pump = launch {
                    camera.frames.collect { frame -> machine.onFrame(frame) }
                }
                try {
                    // StateFlow replays its current value, so a session that
                    // already failed before this subscribes is still observed.
                    val evidenceImages = mutableListOf<CapturedEvidence>()
                    var finalStill: CapturedJpeg? = null
                    var done: LivenessState.Done? = null
                    while (done == null) {
                        when (val gate = machine.state.first {
                            it is LivenessState.Capturing ||
                                it is LivenessState.CapturingEvidence ||
                                it is LivenessState.Done ||
                                it is LivenessState.Failed
                        }) {
                            is LivenessState.Failed -> {
                                FaceCheckLogger.info { "liveness failed: ${gate.reason.name}" }
                                throw FaceCheckException(
                                    code = gate.reason.toErrorCode(),
                                    message = gate.reason.messageEs,
                                )
                            }

                            LivenessState.Capturing -> {
                                val still = camera.captureStill()
                                finalStill = still
                                machine.completeCapture()
                            }

                            is LivenessState.CapturingEvidence -> {
                                val still = camera.captureStill()
                                finalStill = still
                                evidenceImages += CapturedEvidence(gate.role, still)
                                machine.completeEvidenceCapture(gate.role)
                            }

                            is LivenessState.Done -> done = gate
                            else -> Unit
                        }
                        done = done ?: machine.state.value as? LivenessState.Done
                    }

                    FaceCheckLogger.info {
                        "liveness passed in ${done.evidence.durationMs} ms with " +
                            "${done.evidence.challenges.size} challenges; evidence images=" +
                            "${evidenceImages.size}"
                    }
                    LivenessCapture(
                        still = checkNotNull(finalStill) { "liveness ended without a captured still" },
                        evidence = done.evidence,
                        evidenceBundle = if (evidenceImages.isEmpty()) {
                            CapturedEvidenceBundle(
                                EvidenceRole.entries.map { role ->
                                    CapturedEvidence(role, checkNotNull(finalStill))
                                },
                            )
                        } else {
                            CapturedEvidenceBundle(evidenceImages)
                        },
                    )
                } finally {
                    // The camera keeps producing frames after the session ends;
                    // without this the collector outlives the scope's purpose.
                    pump.cancel()
                }
            }
        }
    } catch (expired: TimeoutCancellationException) {
        machine.cancel()
        throw FaceCheckException(
            code = FaceCheckErrorCode.TIMEOUT,
            message = "La verificación tardó demasiado. Vuelve a intentarlo.",
            cause = expired,
        )
    } finally {
        camera.stop()
    }
}

/**
 * A cancelled session is the user's own decision, not a liveness failure; the
 * distinction matters because a host app should not show "no pudimos verificarte"
 * to someone who pressed the back button.
 */
private fun FailureReason.toErrorCode(): FaceCheckErrorCode = when (this) {
    FailureReason.CANCELLED -> FaceCheckErrorCode.CANCELLED
    FailureReason.TIMEOUT -> FaceCheckErrorCode.TIMEOUT
    else -> FaceCheckErrorCode.LIVENESS_FAILED
}
