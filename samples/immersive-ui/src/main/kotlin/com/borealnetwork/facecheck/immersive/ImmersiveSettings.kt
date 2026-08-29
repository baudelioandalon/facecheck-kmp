package com.borealnetwork.facecheck.immersive

/** Configuration supplied by an application host, never loaded by the UI module. */
class ImmersiveSettings(
    baseUrl: String = "",
    apiKey: String = "",
    subjectId: String = "",
) {

    val baseUrl: String = baseUrl.trim().removeSuffix("/")
    val apiKey: String = apiKey.trim()
    val subjectId: String = subjectId.trim()

    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && subjectId.isNotBlank()

    val environmentLabel: String
        get() = when {
            apiKey.startsWith("lk_live_") -> "LIVE"
            apiKey.startsWith("lk_test_") -> "TEST"
            else -> "UNCONFIGURED"
        }

    override fun toString(): String = "ImmersiveSettings(" +
        "baseUrl=$baseUrl, " +
        "apiKey=${redacted(apiKey)}, " +
        "subjectId=$subjectId, " +
        "environment=$environmentLabel" +
        ")"

    private fun redacted(value: String): String = when {
        value.isBlank() -> "<empty>"
        value.length <= KEY_PREVIEW_LENGTH -> value.take(4) + "…"
        else -> value.take(KEY_PREVIEW_LENGTH) + "…"
    }

    private companion object {
        const val KEY_PREVIEW_LENGTH = 12
    }
}
