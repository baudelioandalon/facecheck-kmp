package com.borealnetwork.facecheck

import com.borealnetwork.facecheck.model.FaceCheckErrorCode

/**
 * Opt-in diagnostics for integrators.
 *
 * Off by default, and **structurally incapable of leaking the two things that
 * matter**: the API key and image bytes.
 *
 * That is not a convention to be careful about, it is enforced by the shape of
 * the API. Every message goes through [redact] before it reaches a sink, so a
 * key pasted into a log line by accident — including one inside a URL, an
 * exception message, or a response body echoed back by a proxy — comes out
 * masked. And nothing here accepts a `ByteArray`: to mention a photo at all you
 * have to call [describeBytes], which reports a size and nothing else. A
 * biometric SDK that printed a selfie into logcat would be handing the user's
 * face to every other app that can read logs.
 *
 * Not thread-safe to configure; set [level] and [sink] once during app startup.
 */
object FaceCheckLogger {

    /** Nothing above this level is emitted. [FaceCheckLogLevel.NONE] silences all. */
    var level: FaceCheckLogLevel = FaceCheckLogLevel.NONE

    /** Where lines go. Defaults to stdout, which is logcat on Android. */
    var sink: FaceCheckLogSink = FaceCheckLogSink { logLevel, message ->
        println("[FaceCheck/${logLevel.name}] $message")
    }

    fun error(message: () -> String) = log(FaceCheckLogLevel.ERROR, message)

    fun warn(message: () -> String) = log(FaceCheckLogLevel.WARN, message)

    fun info(message: () -> String) = log(FaceCheckLogLevel.INFO, message)

    fun debug(message: () -> String) = log(FaceCheckLogLevel.DEBUG, message)

    /**
     * Emits the SDK's safe failure envelope.
     *
     * It deliberately accepts no remote message, URL, subject identifier or
     * response details. Integrators can diagnose an outcome without turning a
     * backend response into a logcat payload.
     */
    fun warnFailure(
        operation: FaceCheckLogOperation,
        code: FaceCheckErrorCode,
        httpStatus: Int?,
        retryable: Boolean,
    ) = warn {
        "operation=${operation.wire} code=${code.wire} " +
            "httpStatus=${httpStatus ?: "none"} retryable=$retryable"
    }

    /**
     * The only way to mention image data in a log line.
     *
     * Returns a size, never content. Deliberately the sole overload that touches
     * a byte count, so "log the selfie" is not something a caller can express.
     */
    fun describeBytes(size: Int): String = "<$size bytes>"

    private inline fun log(at: FaceCheckLogLevel, message: () -> String) {
        // The lambda is not invoked at all when the level is off, so building a
        // debug string costs nothing in production.
        if (at.ordinal > level.ordinal || level == FaceCheckLogLevel.NONE) return
        sink.write(at, redact(message()))
    }

    /**
     * Mask API keys and email local parts anywhere in a string.
     *
     * Applied to every line rather than at call sites, because the leak that
     * actually happens is the one nobody wrote on purpose: a server error echoed
     * verbatim, an exception whose message contains the request URL, a header
     * dump added during a debugging session and never removed.
     */
    internal fun redact(raw: String): String =
        raw.replace(API_KEY_PATTERN) { match ->
            "lk_${match.groupValues[1]}_" + MASK
        }.replace(EMAIL_PATTERN) { match ->
            val local = match.groupValues[1]
            val domain = match.groupValues[2]
            "${local.take(1)}$MASK@$domain"
        }

    private const val MASK = "***"

    /** `lk_test_…` / `lk_live_…` — the two prefixes the backend accepts. */
    private val API_KEY_PATTERN = Regex("""lk_(test|live)_[A-Za-z0-9_\-]+""")

    /** Keeps the domain, which is useful for support, and drops the identity. */
    private val EMAIL_PATTERN = Regex("""([A-Za-z0-9._%+\-]+)@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")
}

/** Operations whose failures may be written to host diagnostics. */
enum class FaceCheckLogOperation(internal val wire: String) {
    ENROLL("enroll"),
    INE_ATTACHMENT("ine_attachment"),
    VERIFY("verify"),
    MODEL_PROFILES("model_profiles"),
    ENCRYPTION_KEY("encryption_key"),
    LIVENESS_SESSION("liveness_session"),
}

/** Severity, ordered so a smaller ordinal is more severe. */
enum class FaceCheckLogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
}

/** Where log lines go. Implement to bridge into the host app's own logger. */
fun interface FaceCheckLogSink {
    fun write(level: FaceCheckLogLevel, message: String)
}
