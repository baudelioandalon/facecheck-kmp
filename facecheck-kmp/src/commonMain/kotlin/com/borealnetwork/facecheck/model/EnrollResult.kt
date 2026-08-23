package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * The `/enroll` response body: the subject's reference face is now stored.
 *
 * Enrollment is the primitive that binds a face to a subject ID, so it is
 * also the primitive an attacker would use to impersonate one. Replacing an
 * existing enrollment therefore needs both an explicit `overwrite` flag and a
 * selfie that already matches the stored template — possession of the API key
 * is not by itself authority to replace someone's reference face.
 */
@Serializable
data class EnrollResult(
    val enrolled: Boolean,
    val subjectId: String,
    /** `"test"` or `"live"`, decided by the API key's prefix, not by the caller. */
    val mode: String = "",
    /** True when this call replaced an existing reference face. */
    val overwritten: Boolean = false,
    val faceQuality: FaceQuality? = null,
    val ineEnrolled: Boolean = false,
    val ineQuality: FaceQuality? = null,
    /**
     * Explicit document lifecycle state for the enrolled subject.
     *
     * `ineEnrolled` remains as a backwards-compatible summary flag for older
     * callers, but new consumers should prefer this field.
     */
    val documentStatus: IdentityDocumentStatus = IdentityDocumentStatus.NONE,
    val modelVersion: String? = null,
    /**
     * Telemetry only, and currently always null.
     *
     * The available anti-spoofing model scores ~1.0 ("attack") for every input,
     * live faces included, so the backend records it and never gates on it. Do
     * not build a decision on this field. See <https://facecheck.borealnetwork.org/docs/umbrales>.
     */
    val spoofScore: Double? = null,
)
