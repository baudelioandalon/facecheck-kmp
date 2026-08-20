package com.borealnetwork.facecheck.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public, safe model inventory returned by `/modelProfiles`.
 *
 * The byte counts are backend footprint, not files the SDK downloads. They let
 * an integrator understand the cost/benefit of a profile without exposing model
 * URLs, hashes, thresholds or private artifact IDs.
 */
@Serializable
data class ModelProfileCatalog(
    val defaultProfileId: String,
    val profiles: List<ModelProfileSummary>,
) {
    val defaultProfile: ModelProfileSummary?
        get() = profiles.firstOrNull { it.id == defaultProfileId }
}

@Serializable
data class ModelProfileSummary(
    val id: String,
    val rank: Int,
    val displayName: String,
    val availability: String,
    val badge: String? = null,
    @SerialName("artifactBytes")
    val recognizerArtifactBytes: Long,
    val passivePadArtifactBytes: Long? = null,
    val totalArtifactBytes: Long,
)

@Serializable
data class SessionModelProfile(
    val id: String,
    val displayName: String,
    val rank: Int,
)

/**
 * Location captured immediately before session creation.
 *
 * The backend remains authoritative on freshness. The SDK validates shape and
 * finite ranges so an obviously impossible value fails before opening a session.
 */
@Serializable
data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and inside -90..90"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and inside -180..180"
        }
        require(accuracyMeters.isFinite() && accuracyMeters > 0.0) {
            "accuracyMeters must be finite and positive"
        }
    }
}
