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
 * [similarity] and [ineSimilarity] are normalized server results in `0..1`.
 * They are diagnostic result data for the host app, never client-side decision
 * inputs. Thresholds, embeddings, images and storage paths remain private.
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
    /** Selfie-to-enrollment similarity; null when that comparison did not run. */
    val similarity: Double? = null,
    /** Selfie-to-ID similarity; null when no ID comparison ran. */
    val ineSimilarity: Double? = null,
    val faceQuality: FaceQuality? = null,
    val verificationId: String = "",
    /** Location where the verification was completed, when the integrator captured it. */
    val location: OperationLocation? = null,
    /** Telemetry only, currently always null. See [EnrollResult.spoofScore]. */
    val spoofScore: Double? = null,
    /** Backend-derived company identity; null until the account's first real payment. */
    val companyId: String? = null,
) {
    init {
        CompanyIdentity.requireValid(companyId)
    }

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
