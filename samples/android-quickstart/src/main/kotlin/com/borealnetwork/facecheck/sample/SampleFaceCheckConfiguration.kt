package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.FaceCheckLogLevel

/** The quickstart's one SDK configuration for both enrollment and verification. */
internal fun sampleFaceCheckConfig(
    apiKey: String,
    baseUrl: String,
    livenessTimeoutMs: Long,
): FaceCheckConfig = FaceCheckConfig(
    apiKey = apiKey,
    baseUrl = baseUrl,
    livenessTimeoutMs = livenessTimeoutMs,
    liveness = EnrollmentSessionPolicy.livenessConfig,
    logLevel = FaceCheckLogLevel.DEBUG,
)
