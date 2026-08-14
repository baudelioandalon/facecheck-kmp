package com.borealnetwork.facecheck.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SampleFaceCheckConfigurationTest {

    @Test
    fun `verification configuration reuses the enrollment three second liveness policy`() {
        val config = sampleFaceCheckConfig(
            apiKey = "lk_test_a1b2c3d4e5f6g7h8",
            baseUrl = "https://facecheck.example.com",
            livenessTimeoutMs = 120_000L,
        )

        assertSame(EnrollmentSessionPolicy.livenessConfig, config.liveness)
        assertEquals(3_000L, config.liveness.positioningHoldMs)
    }
}
