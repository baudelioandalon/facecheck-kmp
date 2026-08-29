package com.borealnetwork.facecheck.location

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidLocationPolicyTest {

    @Test
    fun location_fix_timeout_allows_slow_gps_devices_to_respond() {
        assertEquals(30_000L, DEFAULT_LOCATION_FIX_TIMEOUT_MS)
    }
}
