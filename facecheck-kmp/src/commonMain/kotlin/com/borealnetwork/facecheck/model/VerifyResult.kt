package com.borealnetwork.facecheck.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The per-check breakdown inside a [VerifyResult]. */
@Serializable
data class VerifyChecks(
    /** Selfie vs. enrolled reference. Null when the comparison did not run. */
    val faceMatch: Boolean? = null,
    /** Selfie vs. ID portrait. Null when the comparison did not run. */
    val ineMatch: Boolean? = null,
    /** Advisory only; see [livenessEnforced]. */
    val liveness: Boolean? = null,
    /** False in every current deployment: the spoof model cannot gate a decision. */
    val livenessEnforced: Boolean = false,
)

/**
 * The `/verify` response body.
 *
 * There is deliberately no similarity score here, and adding one later would be
 * a security regression rather than a feature. A score, together with the
 * threshold it is compared against, turns `/verify` into a distance oracle:
 * whoever holds the API key can optimise a face morph against the returned
 * number and climb to `verified = true` against a template they have never
 * seen — and the converged image approximately reconstructs the enrolled face.
 * The scores are still written to the tenant's dashboard, where the person
 * being probed cannot read them.
 */
@Serializable
data class VerifyResult(
    val verified: Boolean,
    /** Machine-readable failure reason; null when [verified]. */
    val reason: String? = null,
    /** Spanish prose for the failure, safe to show the user verbatim. */
    val message: String? = null,
    @SerialName("compareWith")
    val compareWithWire: String = CompareWith.ENROLLMENT.wire,
    val checks: VerifyChecks = VerifyChecks(),
    val faceQuality: FaceQuality? = null,
    val verificationId: String = "",
    /** Telemetry only, currently always null. See [EnrollResult.spoofScore]. */
    val spoofScore: Double? = null,
) {
    /** The comparison the backend actually ran, which may be stricter than requested. */
    val compareWith: CompareWith
        get() = CompareWith.fromWire(compareWithWire)

    /**
     * What to tell the user after a failure: the backend's own explanation when
     * it gave one, otherwise a coaching hint derived from the image quality.
     */
    val messageEs: String?
        get() = when {
            verified -> null
            message != null -> message
            else -> faceQuality?.hintEs
        }
}