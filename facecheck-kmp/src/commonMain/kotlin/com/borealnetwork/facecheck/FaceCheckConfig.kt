package com.borealnetwork.facecheck

import com.borealnetwork.facecheck.liveness.ChallengePlan
import com.borealnetwork.facecheck.liveness.LivenessConfig
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException

/** Which universe of data a key reaches: sandbox or production. */
enum class FaceCheckMode(val wire: String) {
    /** `lk_test_…`. Free, metered, capped, and isolated from live subjects. */
    TEST("test"),

    /** `lk_live_…`. Billable, and the only mode that touches real enrollments. */
    LIVE("live"),
}

/**
 * Everything the SDK needs to talk to a FaceCheck deployment.
 *
 * ### The API key is not a secret
 *
 * It ships inside an app the SDK's users install, so anyone willing to unzip an
 * APK has it. The backend is built on that assumption — replacing an enrollment
 * needs a matching face rather than just a valid key. [VerifyResult] may carry
 * normalized comparison scores, but never tenant thresholds or templates. Do
 * not add controls here that assume the key is confidential; put them in the
 * backend, where they hold.
 *
 * ### Thresholds are not here
 *
 * Match thresholds live in the tenant's Firestore config and are never sent to
 * the device. A threshold on the client is a threshold the client can change.
 */
data class FaceCheckConfig(
    /** `lk_test_…` or `lk_live_…`, from the portal. */
    val apiKey: String,

    /** Base URL of the inference deployment, with or without a trailing slash. */
    val baseUrl: String,

    /**
     * How many challenges a session asks for.
     *
     * Two is the default because it is the point where the session stops being
     * defeatable by a printed photo and has not yet become long enough that real
     * users abandon it. More challenges do not make the SDK meaningfully harder
     * to defeat — see
     * [ChallengeMachine][com.borealnetwork.facecheck.liveness.ChallengeMachine] — they just
     * cost completion rate.
     */
    val challengeCount: Int = 2,

    /** TCP connect deadline. */
    val connectTimeoutMs: Long = 15_000,

    /**
     * Whole-request deadline.
     *
     * Generous because the backend runs MTCNN plus ArcFace on a cold instance in
     * the worst case, and a client that gives up early still gets billed for the
     * inference it abandoned.
     */
    val requestTimeoutMs: Long = 60_000,

    /** Socket read deadline. */
    val socketTimeoutMs: Long = 60_000,

    /** Retries after the first attempt, for 5xx and network errors only. */
    val maxRetries: Int = 2,

    /** First backoff step; doubles per attempt, with jitter. */
    val retryBaseDelayMs: Long = 500,

    /** Deadline for the whole on-device liveness session, all challenges included. */
    val livenessTimeoutMs: Long = 90_000,

    /** Per-step thresholds and deadlines for the liveness session. */
    val liveness: LivenessConfig = LivenessConfig(),

    /** Diagnostics. Off by default; never prints keys or image bytes. */
    val logLevel: FaceCheckLogLevel = FaceCheckLogLevel.NONE,
) {
    /**
     * Read off the key prefix, never chosen by the caller.
     *
     * The backend decides mode from the key it validates, so a config field
     * saying otherwise would only ever be a lie the SDK told itself.
     */
    val mode: FaceCheckMode = when {
        apiKey.startsWith(TEST_PREFIX) -> FaceCheckMode.TEST
        apiKey.startsWith(LIVE_PREFIX) -> FaceCheckMode.LIVE
        else -> throw invalid(
            "La llave de API debe empezar con '$TEST_PREFIX' o '$LIVE_PREFIX'.",
        )
    }

    /** [baseUrl] without a trailing slash, so path joins never double up. */
    val normalizedBaseUrl: String = baseUrl.trim().trimEnd('/')

    init {
        if (apiKey.length < MIN_KEY_LENGTH) {
            throw invalid("La llave de API está incompleta.")
        }
        if (apiKey.any { it.isWhitespace() }) {
            throw invalid("La llave de API contiene espacios.")
        }
        if (!normalizedBaseUrl.startsWith("http://") &&
            !normalizedBaseUrl.startsWith("https://")
        ) {
            throw invalid("baseUrl debe ser una URL http(s) completa.")
        }
        // A production key against a cleartext endpoint would put a selfie and a
        // subject ID on the wire in the clear. Refused outright rather than warned
        // about, because the SDK will not get a second chance to be noticed.
        if (mode == FaceCheckMode.LIVE && normalizedBaseUrl.startsWith("http://")) {
            throw invalid("Una llave 'lk_live_' exige HTTPS.")
        }
        if (challengeCount !in ChallengePlan.MIN_CHALLENGES..ChallengePlan.MAX_CHALLENGES) {
            throw invalid(
                "challengeCount debe estar entre ${ChallengePlan.MIN_CHALLENGES} y " +
                    "${ChallengePlan.MAX_CHALLENGES}.",
            )
        }
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0 || socketTimeoutMs <= 0) {
            throw invalid("Los timeouts deben ser mayores a cero.")
        }
        if (maxRetries < 0) throw invalid("maxRetries no puede ser negativo.")
        if (retryBaseDelayMs <= 0) throw invalid("retryBaseDelayMs debe ser mayor a cero.")
        if (livenessTimeoutMs <= 0) throw invalid("livenessTimeoutMs debe ser mayor a cero.")
    }

    /** Absolute URL for an endpoint path. */
    internal fun endpoint(path: String): String = "$normalizedBaseUrl/${path.trimStart('/')}"

    /**
     * Redacted on purpose: a config lands in crash reports and bug tickets, and
     * the default `data class` rendering would carry the API key into both.
     */
    override fun toString(): String =
        "FaceCheckConfig(mode=${mode.wire}, baseUrl=$normalizedBaseUrl, " +
            "challengeCount=$challengeCount, apiKey=${apiKey.take(KEY_PREFIX_SHOWN)}…)"

    private fun invalid(message: String): FaceCheckException =
        FaceCheckException(FaceCheckErrorCode.INVALID_CONFIG, message)

    companion object {
        const val TEST_PREFIX: String = "lk_test_"
        const val LIVE_PREFIX: String = "lk_live_"

        /** Prefix plus enough random characters to not be a placeholder. */
        private const val MIN_KEY_LENGTH = 16

        /** Matches the portal's own key preview: `lk_live_a1b2…`. */
        private const val KEY_PREFIX_SHOWN = 12
    }
}
