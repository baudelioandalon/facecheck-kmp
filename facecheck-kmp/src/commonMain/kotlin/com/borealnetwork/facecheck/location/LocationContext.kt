package com.borealnetwork.facecheck.location

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Maximum time allowed for a platform provider to return a fresh location fix. */
internal const val DEFAULT_LOCATION_FIX_TIMEOUT_MS = 30_000L

/** Maximum age accepted for a location returned by the platform. */
internal const val MAX_LOCATION_FIX_AGE_MS = 120_000L

/** Exact location captured immediately before an operation opens the camera. */
data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant = Clock.System.now(),
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and between -90 and 90"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and between -180 and 180"
        }
        require(accuracyMeters.isFinite() && accuracyMeters > 0.0) {
            "accuracyMeters must be finite and greater than zero"
        }
    }

    /** JSON-safe representation consumed by /livenessSessions and /attachIne. */
    internal fun toWire(): LocationContextWire = LocationContextWire(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        capturedAt = capturedAt.toString(),
    )
}

@Serializable
internal data class LocationContextWire(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: String,
)

/** Host/platform seam. The implementation must request permission before reading location. */
fun interface LocationContextProvider {
    suspend fun currentLocation(): LocationContext
}
