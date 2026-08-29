package com.borealnetwork.facecheck.location

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocationContextTest {
    @Test
    fun retains_exact_coordinates_and_timestamp() {
        val capturedAt = Instant.parse("2026-08-14T12:00:00Z")
        val location = LocationContext(19.4326, -99.1332, 35.0, capturedAt)

        assertEquals(19.4326, location.latitude)
        assertEquals(-99.1332, location.longitude)
        assertEquals(35.0, location.accuracyMeters)
        assertEquals(capturedAt, location.capturedAt)
        assertEquals("2026-08-14T12:00:00Z", location.toWire().capturedAt)
    }

    @Test
    fun rejects_invalid_coordinates_and_accuracy() {
        assertFailsWith<IllegalArgumentException> { LocationContext(91.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { LocationContext(0.0, -181.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { LocationContext(0.0, 0.0, 0.0) }
    }
}
