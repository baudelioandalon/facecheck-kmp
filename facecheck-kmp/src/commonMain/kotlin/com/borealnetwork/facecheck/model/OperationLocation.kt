package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * Location captured by the operation that produced this result.
 *
 * The backend owns [mapsUrl]; clients should render it as a link instead of
 * rebuilding provider-specific map URLs.
 */
@Serializable
data class OperationLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: String? = null,
    val source: String = "unknown",
    val mapsUrl: String = "",
)
