package com.borealnetwork.facecheck.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Android provider that completes permission and location acquisition before camera start. */
class AndroidLocationContextProvider(
    host: CameraHost,
    private val timeoutMs: Long = DEFAULT_LOCATION_FIX_TIMEOUT_MS,
) : LocationContextProvider {
    private val activity = host.context.findComponentActivity()
    private val context: Context = activity

    override suspend fun currentLocation(): LocationContext {
        ensureFinePermission()
        val location = withTimeoutOrNull(timeoutMs) { requestFix() }
            ?: throw FaceCheckException(FaceCheckErrorCode.LOCATION_UNAVAILABLE)
        val now = Clock.System.now()
        if (location.accuracy <= 0f || location.time <= 0L ||
            now.toEpochMilliseconds() - location.time > MAX_LOCATION_FIX_AGE_MS
        ) {
            throw FaceCheckException(FaceCheckErrorCode.LOCATION_UNAVAILABLE)
        }
        return LocationContext(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            capturedAt = now,
        )
    }

    private suspend fun ensureFinePermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        val granted = suspendCancellableCoroutine { continuation ->
            val key = "facecheck-location-${System.nanoTime()}"
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                launcher.unregister()
                continuation.resume(
                    result[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED,
                )
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        if (!granted) throw FaceCheckException(FaceCheckErrorCode.LOCATION_PERMISSION_REQUIRED)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(): Location = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        fun complete(result: Result<Location>) {
            if (!completed.compareAndSet(false, true)) return
            continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation { completed.set(true) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            complete(Result.failure(FaceCheckException(FaceCheckErrorCode.LOCATION_PERMISSION_REQUIRED)))
            return@suspendCancellableCoroutine
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val provider = providers
            .distinct()
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: run {
                complete(Result.failure(FaceCheckException(FaceCheckErrorCode.LOCATION_UNAVAILABLE)))
                return@suspendCancellableCoroutine
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location ->
                if (location == null) {
                    val recent = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    if (recent == null) {
                        complete(Result.failure(FaceCheckException(FaceCheckErrorCode.LOCATION_UNAVAILABLE)))
                    } else {
                        complete(Result.success(recent))
                    }
                } else {
                    complete(Result.success(location))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val last = manager.getLastKnownLocation(provider)
            if (last == null) {
                complete(Result.failure(FaceCheckException(FaceCheckErrorCode.LOCATION_UNAVAILABLE)))
            } else {
                complete(Result.success(last))
            }
        }
    }

}

private fun Context.findComponentActivity(): ComponentActivity {
    var current: Context? = this
    while (current != null) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    throw FaceCheckException(
        FaceCheckErrorCode.LOCATION_UNAVAILABLE,
        "La ubicación necesita el Context de una Activity.",
    )
}
