package com.borealnetwork.facecheck.sample

import android.Manifest

/** Runtime permissions that must be accepted before this security-oriented sample starts a session. */
internal object RequiredPermissions {
    fun forSdk(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(
            if (sdkInt >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    fun isSatisfied(sdkInt: Int, granted: Set<String>): Boolean {
        val hasLocation =
            Manifest.permission.ACCESS_COARSE_LOCATION in granted ||
                Manifest.permission.ACCESS_FINE_LOCATION in granted
        val storagePermission =
            if (sdkInt >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return Manifest.permission.CAMERA in granted && hasLocation && storagePermission in granted
    }
}
