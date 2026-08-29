package com.borealnetwork.facecheck.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Safe contract returned by the owner's trusted backend for a hosted web enrollment.
 *
 * SDKs consume and share [hostedUrl]; they never manufacture this credential or
 * receive the app's API key/enrollment secret through this model.
 */
@Serializable
data class HostedEnrollmentSession(
    val version: String,
    val sessionId: String,
    val operation: String,
    val subjectId: String,
    val mode: String,
    val hostedUrl: String,
    val expiresAt: String,
    /** Backend-derived company identity; null until the account's first real payment. */
    val companyId: String? = null,
) {
    init {
        require(version == "1.0") { "Unsupported hosted enrollment contract version." }
        require(operation == "enroll") { "Hosted enrollment contract must use enroll." }
        require(mode == "test" || mode == "live") { "Hosted enrollment mode must be test or live." }
        require(subjectId.isNotBlank()) { "Hosted enrollment subjectId is required." }
        CompanyIdentity.requireValid(companyId)
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Hosted enrollment sessionId is invalid." }
        val escapedSessionId = Regex.escape(sessionId)
        val hostedUrlPattern = Regex(
            "^https://[^/?#]+/session/$escapedSessionId#token=fcw_launch\\.$escapedSessionId\\.[A-Za-z0-9_-]{43}$",
        )
        require(hostedUrlPattern.matches(hostedUrl)) {
            "Hosted enrollment URL is not bound to its one-time session credential."
        }
        require(runCatching { Instant.parse(expiresAt) }.isSuccess) {
            "Hosted enrollment expiry must be a valid ISO-8601 instant."
        }
    }

    /** Exact bearer URL to pass to a browser/share sheet. */
    val shareUrl: String get() = shareUrlAt(Clock.System.now())

    /** Returns the bearer URL only while the owner-generated session is valid. */
    fun shareUrlAt(now: Instant): String {
        require(Instant.parse(expiresAt) > now) { "Hosted enrollment session expired." }
        return hostedUrl
    }

    private companion object {
        val SESSION_ID_PATTERN = Regex("^ws_[A-Za-z0-9_-]{20,80}$")
    }
}
