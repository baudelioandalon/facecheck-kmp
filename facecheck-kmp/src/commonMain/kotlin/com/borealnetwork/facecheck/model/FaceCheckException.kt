package com.borealnetwork.facecheck.model

import kotlinx.datetime.Instant

/**
 * Every failure the SDK surfaces, from a rejected API key to a liveness session
 * the user abandoned.
 *
 * [code] is the stable machine-readable identifier an integrator branches on;
 * [message] is Spanish prose that can be shown to the end user verbatim. When
 * the failure came from the backend the message is the backend's own — it knows
 * more about the specific rejection than the enum does — and falls back to
 * [FaceCheckErrorCode.messageEs] otherwise.
 */
class FaceCheckException(
    val code: FaceCheckErrorCode,
    override val message: String = code.messageEs,
    val httpStatus: Int? = null,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * Whether retrying the exact same request could plausibly succeed.
     *
     * False for anything the caller has to fix first (a bad key, an unenrolled
     * subject ID, a face that does not match), true for transport failures and
     * server faults. A [RATE_LIMITED][FaceCheckErrorCode.RATE_LIMITED] is
     * retryable, but only after [retryAfterSeconds].
     */
    val isRetryable: Boolean
        get() = code.isRetryable

    /** Seconds the backend asked the caller to wait, when it said so. */
    val retryAfterSeconds: Int?
        get() = details["retryAfterSeconds"]?.toIntOrNull()

    /**
     * When the subject's lockout expires, when the backend reported one.
     *
     * Parsed rather than passed through as a string so a caller can render a
     * countdown without reimplementing ISO-8601. Returns null on an unparseable
     * value instead of throwing: a malformed timestamp in an error response
     * must not replace the error the caller actually needs to see.
     */
    val lockedUntil: Instant?
        get() = details["lockedUntil"]?.let { runCatching { Instant.parse(it) }.getOrNull() }

    override fun toString(): String = "FaceCheckException(${code.name}): $message"
}

/**
 * Wire codes from the backend's `{"error":{"code","message"}}` envelope, plus
 * the codes the SDK raises on its own before a request is ever sent.
 *
 * Keep in sync with `docs/SCHEMA.md` and `functions-python/facecheck/http_io.py`.
 * [UNKNOWN] exists so a code added to the backend after an app shipped
 * degrades to a generic message instead of crashing the parse.
 */
enum class FaceCheckErrorCode(
    val wire: String,
    val messageEs: String,
    val isRetryable: Boolean = false,
) {
    // --- Authentication and tenancy -----------------------------------------
    MISSING_API_KEY(
        "MISSING_API_KEY",
        "Falta la llave de API. Revisa la configuración del SDK.",
    ),
    INVALID_API_KEY(
        "INVALID_API_KEY",
        "La llave de API no es válida o fue revocada. Genera una nueva en el portal.",
    ),
    APP_DISABLED(
        "APP_DISABLED",
        "Esta aplicación está suspendida. Contacta al administrador de la cuenta.",
    ),
    KEY_SERVICE_UNAVAILABLE(
        "KEY_SERVICE_UNAVAILABLE",
        "El servicio de verificación de llaves no está disponible. Intenta de nuevo.",
        isRetryable = true,
    ),
    ENCRYPTED_REQUEST_REQUIRED(
        "ENCRYPTED_REQUEST_REQUIRED",
        "La solicitud debe enviarse cifrada. Actualiza la configuración del SDK.",
    ),
    ENCRYPTION_KEY_STALE(
        "ENCRYPTION_KEY_STALE",
        "La clave de cifrado cambió. Sincronizando y reintentando.",
        isRetryable = true,
    ),
    ENCRYPTION_KEY_UNAVAILABLE(
        "ENCRYPTION_KEY_UNAVAILABLE",
        "No se pudo sincronizar la clave de cifrado. Intenta de nuevo.",
        isRetryable = true,
    ),
    ENCRYPTED_REQUEST_INVALID(
        "ENCRYPTED_REQUEST_INVALID",
        "No se pudo proteger la solicitud. Intenta de nuevo.",
    ),
    COMPANY_NOT_ASSIGNED(
        "COMPANY_NOT_ASSIGNED",
        "Esta cuenta todavía no tiene una compañía porque aún no registra su primer pago real.",
    ),
    COMPANY_NOT_FOUND(
        "COMPANY_NOT_FOUND",
        "La compañía autorizada ya no existe o fue eliminada.",
    ),
    COMPANY_ACCESS_DENIED(
        "COMPANY_ACCESS_DENIED",
        "La cuenta autenticada no tiene acceso a esta compañía.",
    ),
    COMPANY_MIGRATION_IN_PROGRESS(
        "COMPANY_MIGRATION_IN_PROGRESS",
        "La compañía está en migración. Intenta cuando termine la operación administrativa.",
    ),
    COMPANY_SERVICE_ENDED(
        "COMPANY_SERVICE_ENDED",
        "El periodo pagado de la compañía terminó. No se pueden iniciar operaciones nuevas.",
    ),
    COMPANY_DELETE_NOT_APPROVED(
        "COMPANY_DELETE_NOT_APPROVED",
        "La eliminación de la compañía todavía no cuenta con aprobación de superadministrador.",
    ),
    COMPANY_DELETING(
        "COMPANY_DELETING",
        "La eliminación irreversible de la compañía está en curso.",
    ),
    COMPANY_DELETION_FAILED(
        "COMPANY_DELETION_FAILED",
        "La eliminación de la compañía se detuvo y requiere intervención operativa.",
    ),

    // --- Request shape -------------------------------------------------------
    MISSING_SUBJECT_ID("MISSING_SUBJECT_ID", "Falta el identificador del sujeto."),
    INVALID_SUBJECT_ID("INVALID_SUBJECT_ID", "El identificador del sujeto no tiene un formato válido."),
    /** Legacy backend codes retained only so older responses can still be decoded. */
    MISSING_EMAIL("MISSING_EMAIL", "Falta el identificador del sujeto."),
    INVALID_EMAIL("INVALID_EMAIL", "El identificador del sujeto no tiene un formato válido."),
    MISSING_FILE("MISSING_FILE", "No se envió la imagen requerida. Vuelve a intentarlo."),
    EMPTY_FILE("EMPTY_FILE", "La imagen llegó vacía. Vuelve a tomar la foto."),
    INVALID_IMAGE("INVALID_IMAGE", "No se pudo leer la imagen. Vuelve a tomar la foto."),
    IMAGE_TOO_LARGE("IMAGE_TOO_LARGE", "La imagen es demasiado grande. Reduce la resolución."),
    REQUEST_TOO_LARGE("REQUEST_TOO_LARGE", "La petición es demasiado grande."),
    UNSUPPORTED_MEDIA_TYPE(
        "UNSUPPORTED_MEDIA_TYPE",
        "Formato de imagen no soportado. Usa JPEG, PNG o WebP.",
    ),
    INVALID_COMPARE_WITH(
        "INVALID_COMPARE_WITH",
        "El parámetro de comparación no es válido.",
    ),
    INVALID_FLAG("INVALID_FLAG", "Un parámetro de la petición no es válido."),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "Método HTTP no permitido."),
    MODEL_PROFILE_NOT_ALLOWED(
        "MODEL_PROFILE_NOT_ALLOWED",
        "El modelo solicitado no está permitido para esta aplicación.",
    ),
    MODEL_PROFILE_UNAVAILABLE(
        "MODEL_PROFILE_UNAVAILABLE",
        "El modelo solicitado no está disponible en el backend.",
    ),
    MODEL_PROFILE_MISMATCH(
        "MODEL_PROFILE_MISMATCH",
        "El modelo no coincide con la sesión de liveness.",
    ),

    // --- Face pipeline -------------------------------------------------------
    NO_FACE(
        "NO_FACE",
        "No se detectó ningún rostro. Acércate a la cámara, con buena luz y sin nada " +
            "que te cubra la cara.",
    ),
    NO_FACE_IN_INE(
        "NO_FACE_IN_INE",
        "No se detectó el rostro en la identificación. Tómala de frente, sin reflejos y " +
            "con la credencial completa.",
    ),
    INE_MISMATCH(
        "INE_MISMATCH",
        "El rostro de la INE no coincide con el rostro enrolado.",
    ),

    // --- Subject lifecycle ---------------------------------------------------
    NOT_ENROLLED(
        "NOT_ENROLLED",
        "Este identificador no tiene un registro facial. Regístralo antes de verificar.",
    ),
    SUBJECT_ALREADY_ENROLLED(
        "SUBJECT_ALREADY_ENROLLED",
        "Ese identificador ya está enrolado. Para reemplazar la foto de referencia usa " +
            "overwrite = true con una selfie de la persona ya registrada.",
    ),
    REENROLLMENT_FACE_MISMATCH(
        "REENROLLMENT_FACE_MISMATCH",
        "La selfie no coincide con el registro facial actual de ese identificador.",
    ),

    // --- Enrollment grants ---------------------------------------------------
    // Both are integration errors, never something the end user can fix, so the
    // messages address the developer reading the log.
    ENROLLMENT_GRANT_REQUIRED(
        "ENROLLMENT_GRANT_REQUIRED",
        "Falta el grant de enrolamiento. Tu backend debe firmarlo y pasarlo a " +
            "enroll(grant = ...). Consulta docs/ENROLLMENT_GRANTS.md.",
    ),
    ENROLLMENT_GRANT_INVALID(
        "ENROLLMENT_GRANT_INVALID",
        "El grant de enrolamiento no es válido: revisa la firma, el identificador, el " +
            "modo (test/live), su vigencia, y que no se haya usado antes.",
    ),
    MODEL_VERSION_MISMATCH(
        "MODEL_VERSION_MISMATCH",
        "El registro facial se creó con una versión anterior del modelo. Vuelve a " +
            "registrar a esta persona.",
    ),
    ENROLLMENT_INCOMPLETE(
        "ENROLLMENT_INCOMPLETE",
        "El registro facial está incompleto. Vuelve a registrar a esta persona.",
    ),
    INE_NOT_ENROLLED(
        "INE_NOT_ENROLLED",
        "Esta persona no tiene una identificación registrada.",
    ),

    // --- Active Liveness sessions -------------------------------------------
    LOCATION_REQUIRED(
        "LOCATION_REQUIRED",
        "Se requiere ubicación para iniciar la verificación.",
    ),
    LOCATION_STALE(
        "LOCATION_STALE",
        "La ubicación ya no está fresca. Vuelve a intentar.",
        isRetryable = true,
    ),
    MISSING_LIVENESS_SESSION(
        "MISSING_LIVENESS_SESSION",
        "Falta la sesión de liveness. Vuelve a iniciar la captura.",
    ),
    LIVENESS_SESSION_EXPIRED(
        "LIVENESS_SESSION_EXPIRED",
        "La sesión expiró. Vuelve a empezar.",
        isRetryable = true,
    ),
    LIVENESS_SESSION_MISMATCH(
        "LIVENESS_SESSION_MISMATCH",
        "La sesión no coincide con esta solicitud.",
    ),
    LIVENESS_SESSION_INVALID(
        "LIVENESS_SESSION_INVALID",
        "La solicitud de sesión de liveness no es válida.",
    ),
    LIVENESS_SESSION_CONSUMED(
        "LIVENESS_SESSION_CONSUMED",
        "Esta sesión ya fue utilizada. Crea una nueva e intenta de nuevo.",
    ),
    LIVENESS_FACE_CHANGED(
        "LIVENESS_FACE_CHANGED",
        "Detectamos un cambio de rostro durante la sesión. Vuelve a empezar.",
    ),
    LIVENESS_CHALLENGE_FAILED(
        "LIVENESS_CHALLENGE_FAILED",
        "No se completaron correctamente los movimientos solicitados.",
    ),
    LIVENESS_REPLAY_DETECTED(
        "LIVENESS_REPLAY_DETECTED",
        "Estas capturas ya fueron usadas. Inicia una sesión nueva.",
    ),
    LIVENESS_PROCESSING(
        "LIVENESS_PROCESSING",
        "La sesión aún se está procesando. Intenta de nuevo en unos segundos.",
        isRetryable = true,
    ),

    // --- Limits --------------------------------------------------------------
    QUOTA_EXCEEDED(
        "QUOTA_EXCEEDED",
        "Se agotó la cuota mensual del plan. Actualízalo desde el portal.",
    ),
    RATE_LIMITED(
        "RATE_LIMITED",
        "Demasiados intentos fallidos. Espera unos minutos e intenta de nuevo.",
        isRetryable = true,
    ),

    // --- Server --------------------------------------------------------------
    ENCODE_FAILED("ENCODE_FAILED", "No se pudo procesar la imagen.", isRetryable = true),
    INTERNAL("INTERNAL", "Ocurrió un error inesperado. Intenta de nuevo.", isRetryable = true),

    // --- Raised by the SDK, never by the backend ------------------------------
    NOT_INITIALIZED(
        "NOT_INITIALIZED",
        "FaceCheck no está inicializado. Llama a FaceCheck.initialize() primero.",
    ),
    INVALID_CONFIG(
        "INVALID_CONFIG",
        "La configuración de FaceCheck no es válida.",
    ),
    NETWORK_ERROR(
        "NETWORK_ERROR",
        "No hay conexión con el servidor. Revisa tu internet e intenta de nuevo.",
        isRetryable = true,
    ),
    TIMEOUT(
        "TIMEOUT",
        "La operación tardó demasiado. Intenta de nuevo.",
        isRetryable = true,
    ),
    INVALID_RESPONSE(
        "INVALID_RESPONSE",
        "El servidor devolvió una respuesta que el SDK no pudo leer.",
        isRetryable = true,
    ),
    LIVENESS_FAILED(
        "LIVENESS_FAILED",
        "No se pudo completar la prueba de vida. Intenta de nuevo.",
    ),
    LIVENESS_EVIDENCE_INVALID(
        "LIVENESS_EVIDENCE_INVALID",
        "La evidencia de liveness no es válida. Vuelve a intentarlo.",
    ),
    CAMERA_UNAVAILABLE(
        "CAMERA_UNAVAILABLE",
        "No se pudo usar la cámara. Revisa los permisos de la aplicación.",
    ),
    LOCATION_PERMISSION_REQUIRED(
        "LOCATION_PERMISSION_REQUIRED",
        "Se necesita permiso de ubicación para continuar con mayor seguridad.",
    ),
    LOCATION_UNAVAILABLE(
        "LOCATION_UNAVAILABLE",
        "No se pudo obtener tu ubicación actual. Activa la ubicación e intenta de nuevo.",
    ),
    CANCELLED("CANCELLED", "La verificación fue cancelada."),
    UNKNOWN("UNKNOWN", "Ocurrió un error inesperado. Intenta de nuevo."),
    ;

    companion object {
        private val byWire: Map<String, FaceCheckErrorCode> = entries.associateBy { it.wire }

        /** Maps a backend `error.code` onto the enum, degrading to [UNKNOWN]. */
        fun fromWire(wire: String?): FaceCheckErrorCode =
            wire?.let { byWire[it.trim().uppercase()] } ?: UNKNOWN
    }
}
