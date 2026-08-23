package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Lifecycle state for a subject's document record.
 *
 * The status stays separate from the biometric face state so a subject can be
 * enrolled by face first and complete the document later.
 */
@Serializable
enum class IdentityDocumentStatus {
    @SerialName("none")
    NONE,
    @SerialName("legacy_front_only")
    LEGACY_FRONT_ONLY,
    @SerialName("complete")
    COMPLETE,
    @SerialName("processing_failed")
    PROCESSING_FAILED,
}
