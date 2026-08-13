package com.borealnetwork.facecheck.sample

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredPermissionsTest {

    @Test
    fun `Android 13 requests image media permission`() {
        val permissions = RequiredPermissions.forSdk(33)

        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
    }

    @Test
    fun `Android 12 requests legacy external storage permission`() {
        val permissions = RequiredPermissions.forSdk(32)

        assertTrue(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.READ_MEDIA_IMAGES in permissions)
    }

    @Test
    fun `the gate requires camera location and storage`() {
        val granted = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_MEDIA_IMAGES,
        )

        assertTrue(RequiredPermissions.isSatisfied(33, granted))
        assertFalse(RequiredPermissions.isSatisfied(33, granted - Manifest.permission.CAMERA))
        assertFalse(RequiredPermissions.isSatisfied(33, granted - Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(RequiredPermissions.isSatisfied(33, granted - Manifest.permission.READ_MEDIA_IMAGES))
    }
}
