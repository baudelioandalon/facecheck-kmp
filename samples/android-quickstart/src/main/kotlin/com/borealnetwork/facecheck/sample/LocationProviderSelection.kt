package com.borealnetwork.facecheck.sample

import android.location.LocationManager

internal object LocationProviderSelection {
    val providers: List<String> = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
    )
}
