package com.borealnetwork.facecheck.sample

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionPreconditionsTest {

    @Test
    fun `missing local API key blocks the session before permissions are requested`() {
        val message = SessionPreconditions.blockingMessage(
            apiKey = "",
            sdkInt = 33,
            granted = emptySet(),
        )

        assertEquals("Configura FACECHECK_API_KEY en local.properties antes de continuar.", message)
    }

    @Test
    fun `missing permission blocks the session`() {
        val message = SessionPreconditions.blockingMessage(
            apiKey = "lk_test_example",
            sdkInt = 33,
            granted = setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )

        assertEquals("Acepta cámara, almacenamiento y ubicación antes de iniciar.", message)
    }

    @Test
    fun `configured sample with all permissions may start`() {
        val message = SessionPreconditions.blockingMessage(
            apiKey = "lk_test_example",
            sdkInt = 33,
            granted = setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_MEDIA_IMAGES,
            ),
        )

        assertNull(message)
    }
}
