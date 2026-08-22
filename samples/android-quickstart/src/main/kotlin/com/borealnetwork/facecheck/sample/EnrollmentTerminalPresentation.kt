package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException

/**
 * Android-free UI-safe view of a FaceCheck failure.
 *
 * It deliberately never retains [FaceCheckException.message] or exception details. Those values
 * may originate from a remote service, whereas this presentation uses only the stable error code
 * and the SDK's canonical Spanish text.
 */
internal class FaceCheckFailurePresentation private constructor(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val httpStatus: Int?,
) {
    companion object {
        fun from(error: FaceCheckException): FaceCheckFailurePresentation = from(
            errorCode = error.code,
            httpStatus = error.httpStatus,
        )

        fun from(
            errorCode: FaceCheckErrorCode,
            httpStatus: Int? = null,
        ): FaceCheckFailurePresentation = fromWire(
            code = errorCode.wire,
            retryable = errorCode.isRetryable,
            httpStatus = httpStatus,
        )

        fun fromWire(
            code: String,
            retryable: Boolean,
            httpStatus: Int? = null,
        ): FaceCheckFailurePresentation {
            val knownCode = FaceCheckErrorCode.fromWire(code)
            val isKnownCode = knownCode != FaceCheckErrorCode.UNKNOWN ||
                code.trim().equals(FaceCheckErrorCode.UNKNOWN.wire, ignoreCase = true)

            return FaceCheckFailurePresentation(
                code = if (isKnownCode) knownCode.wire else FaceCheckErrorCode.UNKNOWN.wire,
                message = if (isKnownCode) knownCode.messageEs else FALLBACK_MESSAGE,
                retryable = retryable,
                httpStatus = httpStatus,
            )
        }

        private const val FALLBACK_MESSAGE = "No pudimos completar la operación. Intenta de nuevo."
    }
}

/** Safe terminal state for an enrollment retry card or completion dialog. */
internal class EnrollmentTerminalPresentation private constructor(
    val attempt: EnrollmentAttempt,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val showsLoading: Boolean = false,
) {
    /** UI recapture eligibility depends on attempts remaining, not backend request retryability. */
    val nextAttempt: EnrollmentAttempt? get() = attempt.retry()

    companion object {
        fun from(attempt: EnrollmentAttempt): EnrollmentTerminalPresentation = failure(
            attempt = attempt,
            failure = FaceCheckFailurePresentation.from(FaceCheckErrorCode.ENROLLMENT_INCOMPLETE),
        )

        /**
         * Builds a safe terminal presentation from a legacy call shape.
         *
         * @param message Legacy source-compatible value deliberately ignored for safety. UI text
         *   always comes from the recognized wire code's canonical Spanish message or the fallback.
         */
        fun failure(
            attempt: EnrollmentAttempt,
            code: String,
            @Suppress("UNUSED_PARAMETER")
            message: String,
            retryable: Boolean = false,
        ): EnrollmentTerminalPresentation = failure(
            attempt = attempt,
            failure = FaceCheckFailurePresentation.fromWire(code, retryable),
        )

        fun failure(
            attempt: EnrollmentAttempt,
            failure: FaceCheckFailurePresentation,
        ): EnrollmentTerminalPresentation = EnrollmentTerminalPresentation(
            attempt = attempt,
            code = failure.code,
            message = if (failure.code == FaceCheckErrorCode.UNKNOWN.wire) {
                FALLBACK_MESSAGE
            } else {
                failure.message
            },
            retryable = failure.retryable,
        )

        fun failure(
            attempt: EnrollmentAttempt,
            error: FaceCheckException,
        ): EnrollmentTerminalPresentation = failure(attempt, FaceCheckFailurePresentation.from(error))

        fun failure(
            attempt: EnrollmentAttempt,
            errorCode: FaceCheckErrorCode,
        ): EnrollmentTerminalPresentation = failure(attempt, FaceCheckFailurePresentation.from(errorCode))

        fun completion(attempt: EnrollmentAttempt): EnrollmentTerminalPresentation = EnrollmentTerminalPresentation(
            attempt = attempt,
            code = "ENROLLMENT_COMPLETE",
            message = "El enrolamiento se completó correctamente.",
            retryable = false,
        )

        private const val FALLBACK_MESSAGE = "No pudimos completar el enrolamiento. Intenta de nuevo."
    }
}

/** Safe enrollment failure data retained at the Activity boundary for diagnostics. */
internal class EnrollmentFailureDiagnostic private constructor(
    val presentation: EnrollmentTerminalPresentation,
    val httpStatus: Int?,
    val isLocalEnrollmentIncomplete: Boolean,
) {
    val code: String get() = presentation.code
    val retryable: Boolean get() = presentation.retryable

    companion object {
        fun incomplete(attempt: EnrollmentAttempt): EnrollmentFailureDiagnostic = EnrollmentFailureDiagnostic(
            presentation = EnrollmentTerminalPresentation.from(attempt),
            httpStatus = null,
            isLocalEnrollmentIncomplete = true,
        )

        fun from(
            attempt: EnrollmentAttempt,
            error: FaceCheckException,
        ): EnrollmentFailureDiagnostic {
            val failure = FaceCheckFailurePresentation.from(error)
            return EnrollmentFailureDiagnostic(
                presentation = EnrollmentTerminalPresentation.failure(attempt, failure),
                httpStatus = failure.httpStatus,
                isLocalEnrollmentIncomplete = false,
            )
        }
    }
}
