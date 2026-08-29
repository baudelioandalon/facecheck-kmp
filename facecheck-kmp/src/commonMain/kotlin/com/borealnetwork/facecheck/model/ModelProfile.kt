package com.borealnetwork.facecheck.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public, non-sensitive description of a backend recognition profile. */
@Serializable
data class ModelProfileSummary(
    val id: String,
    val rank: Int,
    val displayName: String,
    val availability: String,
    val badge: String? = null,
    @SerialName("artifactBytes") val recognizerArtifactBytes: Long,
    val passivePadArtifactBytes: Long? = null,
    val totalArtifactBytes: Long,
) {
    /** Decimal MB, matching the portal and backend documentation. */
    fun sizeLabel(): String {
        val recognizer = formatDecimalMb(recognizerArtifactBytes)
        val pad = passivePadArtifactBytes?.let(::formatDecimalMb)
        return buildString {
            append("Reconocimiento ").append(recognizer)
            if (pad != null) append(" · Anti-spoof ").append(pad)
            append(" · Total backend ").append(formatDecimalMb(totalArtifactBytes))
        }
    }
}

@Serializable
data class ModelProfileCatalog(
    val defaultProfileId: String? = null,
    val profiles: List<ModelProfileSummary> = emptyList(),
) {
    /** The server-selected default, never simply the first array element. */
    val defaultProfile: ModelProfileSummary?
        get() = defaultProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

    fun requireDefault(): ModelProfileSummary = defaultProfile
        ?: throw FaceCheckException(
            code = FaceCheckErrorCode.MODEL_PROFILE_UNAVAILABLE,
            message = "El servicio no devolvió un modelo de enrolamiento disponible.",
        )
}

/** Recognition profile pinned to a server-owned active-liveness session. */
@Serializable
data class SessionModelProfile(
    val id: String,
    val displayName: String,
    val rank: Int,
)

/** Fresh device location supplied when creating a server-owned session. */
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

private fun formatDecimalMb(bytes: Long): String {
    val tenths = kotlin.math.round(bytes / 100_000.0).toLong()
    return "${tenths / 10}.${tenths % 10} MB"
}
