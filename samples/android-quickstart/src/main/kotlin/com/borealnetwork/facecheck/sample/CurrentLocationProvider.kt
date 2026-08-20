package com.borealnetwork.facecheck.sample

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.LocationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** Just-in-time Android location adapter for liveness session creation. */
internal class CurrentLocationProvider(context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    suspend fun current(): LocationContext {
        bestFreshLastKnown()?.let { return it.toLocationContext() }
        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            requestOneUpdate()
        } ?: throw FaceCheckException(
            code = FaceCheckErrorCode.LOCATION_REQUIRED,
            message = "No pudimos obtener una ubicación fresca. Revisa permisos y señal.",
        )
        return fresh.toLocationContext()
    }

    @SuppressLint("MissingPermission")
    private fun bestFreshLastKnown(): Location? =
        providers()
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.ageMs() <= MAX_LOCATION_AGE_MS }
            .minByOrNull { it.ageMs() }

    @SuppressLint("MissingPermission")
    private suspend fun requestOneUpdate(): Location? = suspendCancellableCoroutine { cont ->
        val provider = providers().firstOrNull { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        } ?: providers().firstOrNull()

        if (provider == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                cont.resume(location)
            }

            @Deprecated("Deprecated by Android framework.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
    }

    private fun providers(): List<String> = LocationProviderSelection.providers

    private fun Location.toLocationContext(): LocationContext {
        val age = ageMs().coerceAtLeast(0L)
        return LocationContext(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy.takeIf { hasAccuracy() }?.toDouble() ?: DEFAULT_ACCURACY_METERS,
            capturedAt = Clock.System.now() - age.milliseconds,
        )
    }

    private fun Location.ageMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L

    private companion object {
        const val MAX_LOCATION_AGE_MS = 120_000L
        const val LOCATION_TIMEOUT_MS = 10_000L
        const val DEFAULT_ACCURACY_METERS = 500.0
    }
}
