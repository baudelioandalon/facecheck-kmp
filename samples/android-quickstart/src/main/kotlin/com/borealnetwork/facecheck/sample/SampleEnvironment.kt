package com.borealnetwork.facecheck.sample

internal enum class SampleEnvironment(val label: String) {
    TEST("TEST"),
    PRODUCTION("PRODUCTION"),
    UNKNOWN("UNCONFIGURED");

    companion object {
        fun fromApiKey(apiKey: String): SampleEnvironment = when {
            apiKey.startsWith("lk_test_") -> TEST
            apiKey.startsWith("lk_live_") -> PRODUCTION
            else -> UNKNOWN
        }
    }
}
