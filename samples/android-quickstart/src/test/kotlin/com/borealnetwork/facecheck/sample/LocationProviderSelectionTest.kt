package com.borealnetwork.facecheck.sample

import android.location.LocationManager
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationProviderSelectionTest {

    @Test
    fun `network provider is requested before gps for indoor enrollment starts`() {
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER),
            LocationProviderSelection.providers,
        )
    }
}
