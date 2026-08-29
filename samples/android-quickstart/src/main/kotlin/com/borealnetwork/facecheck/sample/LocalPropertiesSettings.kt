package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.immersive.ImmersiveSettings

internal object LocalPropertiesSettings {

    fun from(values: Map<String, String>): ImmersiveSettings = ImmersiveSettings(
        baseUrl = values[BASE_URL].orEmpty(),
        apiKey = values[API_KEY].orEmpty(),
        subjectId = values[SUBJECT_ID].orEmpty(),
    )

    fun fromBuildConfig(): ImmersiveSettings = from(
        mapOf(
            BASE_URL to BuildConfig.FACECHECK_BASE_URL,
            API_KEY to BuildConfig.FACECHECK_API_KEY,
            SUBJECT_ID to BuildConfig.FACECHECK_SUBJECT_ID,
        ),
    )

    private const val BASE_URL = "FACECHECK_BASE_URL"
    private const val API_KEY = "FACECHECK_API_KEY"
    private const val SUBJECT_ID = "FACECHECK_SUBJECT_ID"
}
