package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.internal.sha256Hex
import com.borealnetwork.facecheck.isValidSubjectId
import com.borealnetwork.facecheck.liveness.CapturedEvidenceBundle
import com.borealnetwork.facecheck.liveness.LivenessSessionDescriptor
import com.borealnetwork.facecheck.liveness.LivenessSessionWire
import com.borealnetwork.facecheck.liveness.ServerChallenge
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.LocationContext
import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.VerifyResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.random.Random

/**
 * The HTTP client for the FaceCheck inference endpoints.
 *
 * Two calls, both `multipart/form-data`, both authenticated with the
 * `X-Api-Key` header. Everything a caller sees comes back as either a parsed
 * result or a [FaceCheckException] carrying the backend's own error code and its
 * Spanish message.
 *
 * @param engine injectable so tests drive a `MockEngine`; null uses the
 *   platform engine (OkHttp on Android, Darwin on iOS) picked up from the
 *   classpath.
 * @param sleep injectable so retry tests do not actually wait.
 */
internal class FaceCheckApi(
    private val config: FaceCheckConfig,
    engine: HttpClientEngine? = null,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : FaceCheckBackend {
    private val json = Json {
        // A backend that starts returning one more field must not break apps
        // already in the field; there is no update path for a shipped SDK.
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client: HttpClient = run {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.connectTimeoutMs
                socketTimeoutMillis = config.socketTimeoutMs
            }
            // Status codes are the SDK's own business: a 404 from /verify is
            // NOT_ENROLLED, which is a result the caller handles, not a crash.
            expectSuccess = false
        }
        if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
    }

    /**
     * `POST /enroll`.
     *
     * [grant] is the integrator backend's signed authorisation to enrol this
     * subject; the backend demands one for `lk_live_` keys. It is never logged:
     * it is a bearer credential for one enrollment.
     *
     * [overwrite] maps to the backend's `overwrite` flag. Setting it is not by
     * itself authority to replace a stored face — the backend also requires
     * [selfie] to match the template already on file, because the API key ships
     * inside the tenant's app and cannot be treated as a secret.
     */
    override suspend fun enroll(
        subjectId: String,
        selfie: ByteArray,
        ine: ByteArray?,
        grant: String?,
        overwrite: Boolean,
    ): EnrollResult {
        requireValidSubjectId(subjectId)
        FaceCheckLogger.info {
            "enroll: selfie=${FaceCheckLogger.describeBytes(selfie.size)} " +
                "ine=${ine?.let { FaceCheckLogger.describeBytes(it.size) } ?: "none"} " +
                "grant=${if (grant == null) "none" else "present"} overwrite=$overwrite"
        }
        return post(ENROLL_PATH) {
            append("subjectId", subjectId)
            if (grant != null) append("grant", grant)
            if (overwrite) append("overwrite", "true")
            appendImage("selfie", selfie)
            if (ine != null) appendImage("ine", ine)
        }
    }

    /**
     * `POST /verify`.
     *
     * The response carries no similarity score by design; see [VerifyResult].
     */
    override suspend fun verify(
        subjectId: String,
        selfie: ByteArray,
        compareWith: CompareWith,
    ): VerifyResult {
        requireValidSubjectId(subjectId)
        FaceCheckLogger.info {
            "verify: selfie=${FaceCheckLogger.describeBytes(selfie.size)} " +
                "compareWith=${compareWith.wire}"
        }
        return post(VERIFY_PATH) {
            append("subjectId", subjectId)
            append("compareWith", compareWith.wire)
            appendImage("selfie", selfie)
        }
    }

    override suspend fun getEnrollmentModelProfiles(): ModelProfileCatalog =
        getJson("modelProfiles?operation=enroll")

    override suspend fun createLivenessSession(
        operation: String,
        subjectId: String,
        requestedModelProfileId: String?,
        location: LocationContext,
    ): LivenessSessionDescriptor {
        requireValidSubjectId(subjectId)
        val normalizedOperation = operation.trim().lowercase()
        if (normalizedOperation !in setOf("enroll", "verify")) {
            throw FaceCheckException(
                code = FaceCheckErrorCode.INVALID_CONFIG,
                message = "operation debe ser enroll o verify.",
            )
        }

        val request = LivenessSessionRequest(
            operation = normalizedOperation,
            subjectId = subjectId,
            protocolVersion = ACTIVE_LIVENESS_PROTOCOL,
            sdk = SDK_ID,
            locationContext = location,
            requestedModelProfileId = requestedModelProfileId?.takeIf { it.isNotBlank() },
        )
        val wire = postJsonWithoutRetry<LivenessSessionWire>(
            path = LIVENESS_SESSIONS_PATH,
            body = json.encodeToString(request),
        )
        val challenges = wire.challengePlan.map { item ->
            ServerChallenge.fromWire(item) ?: throw FaceCheckException(
                code = FaceCheckErrorCode.INVALID_RESPONSE,
                message = "El servidor devolvió un reto de vida no soportado.",
            )
        }
        return LivenessSessionDescriptor(
            sessionId = wire.sessionId,
            subjectId = subjectId,
            operation = normalizedOperation,
            expiresAt = wire.expiresAt,
            modelProfile = wire.modelProfile,
            protocolVersion = wire.protocolVersion,
            challengePlan = challenges,
            capturePolicy = wire.capturePolicy,
        )
    }

    override suspend fun enroll(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        grant: String?,
        overwrite: Boolean,
        ine: ByteArray?,
    ): EnrollResult {
        requireSession(session, operation = "enroll")
        return post(ENROLL_PATH) {
            append("subjectId", session.subjectId)
            append("livenessSessionId", session.sessionId)
            append("modelProfileId", session.modelProfileId)
            append("evidenceManifest", evidence.toManifestJson())
            if (grant != null) append("grant", grant)
            if (overwrite) append("overwrite", "true")
            evidence.images.forEachIndexed { index, item ->
                appendImage("evidence_$index", item.jpeg.bytes, filename = "evidence_$index.jpg")
            }
            if (ine != null) appendImage("ine", ine)
        }
    }

    override suspend fun verify(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        compareWith: CompareWith,
    ): VerifyResult {
        requireSession(session, operation = "verify")
        return post(VERIFY_PATH) {
            append("subjectId", session.subjectId)
            append("livenessSessionId", session.sessionId)
            append("evidenceManifest", evidence.toManifestJson())
            append("compareWith", compareWith.wire)
            evidence.images.forEachIndexed { index, item ->
                appendImage("evidence_$index", item.jpeg.bytes, filename = "evidence_$index.jpg")
            }
        }
    }

    override fun close() = client.close()

    private fun requireValidSubjectId(subjectId: String) {
        if (isValidSubjectId(subjectId)) return
        throw FaceCheckException(FaceCheckErrorCode.INVALID_SUBJECT_ID)
    }

    // --- Transport ------------------------------------------------------------

    private suspend inline fun <reified T> getJson(path: String): T {
        val response = client.get(config.endpoint(path)) {
            header(API_KEY_HEADER, config.apiKey)
        }
        if (response.status.isSuccess()) return decodeBody(response)
        throw response.toException()
    }

    private suspend inline fun <reified T> postJsonWithoutRetry(path: String, body: String): T {
        val response = client.post(config.endpoint(path)) {
            header(API_KEY_HEADER, config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.isSuccess()) return decodeBody(response)
        throw response.toException()
    }

    /**
     * One multipart POST, retried on transport failures and 5xx only.
     *
     * **Never on a 4xx.** A 4xx is the backend saying the request itself is
     * wrong, and re-sending it unchanged cannot make it right — but it can do
     * real damage: `/verify` bills the tenant and counts against the subject's
     * lockout streak on every attempt, so retrying a `403` would spend three of
     * a user's five allowed failures on one tap and lock them out of their own
     * account. The narrow rule (5xx and network errors, both of which mean the
     * request may not have been processed at all) is the whole point.
     */
    private suspend inline fun <reified T> post(
        path: String,
        crossinline parts: FormBuilder.() -> Unit,
    ): T {
        var attempt = 0
        while (true) {
            val response = try {
                client.submitFormWithBinaryData(
                    url = config.endpoint(path),
                    formData = formData { parts() },
                ) {
                    header(API_KEY_HEADER, config.apiKey)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Ktor surfaces timeouts and socket errors as engine-specific
                // types; from common code the only portable discriminator is the
                // exception's own name.
                val transport = failure.asTransportFailure()
                if (attempt < config.maxRetries) {
                    FaceCheckLogger.warn {
                        "$path failed (${transport.code.wire}), retry ${attempt + 1} of " +
                            "${config.maxRetries}: ${failure.message}"
                    }
                    sleep(backoffMs(attempt))
                    attempt++
                    continue
                }
                throw transport
            }

            if (response.status.isSuccess()) {
                return decodeBody(response)
            }

            val error = response.toException()
            if (response.status.value >= HTTP_SERVER_ERROR && attempt < config.maxRetries) {
                FaceCheckLogger.warn {
                    "$path returned ${response.status.value}, retry ${attempt + 1} of " +
                        "${config.maxRetries}"
                }
                sleep(backoffMs(attempt))
                attempt++
                continue
            }
            FaceCheckLogger.warn { "$path rejected: ${error.code.wire} ${error.message}" }
            throw error
        }
    }

    private suspend inline fun <reified T> decodeBody(response: HttpResponse): T {
        val body = response.bodyAsText()
        return try {
            json.decodeFromString<T>(body)
        } catch (failure: Exception) {
            throw FaceCheckException(
                code = FaceCheckErrorCode.INVALID_RESPONSE,
                httpStatus = response.status.value,
                cause = failure,
            )
        }
    }

    /** Parse the `{"error":{"code","message","details"}}` envelope. */
    private suspend fun HttpResponse.toException(): FaceCheckException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val envelope = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = envelope?.get("error") as? JsonObject

        val code = FaceCheckErrorCode.fromWire((error?.get("code") as? JsonPrimitive)?.content)
        val message = (error?.get("message") as? JsonPrimitive)?.content
        val details = (error?.get("details") as? JsonObject)
            ?.mapValues { (_, value) -> value.asPlainString() }
            ?.filterValues { it != null }
            ?.mapValues { (_, value) -> value!! }
            ?: emptyMap()

        return FaceCheckException(
            code = code,
            message = message?.takeIf { it.isNotBlank() } ?: code.messageEs,
            httpStatus = status.value,
            details = details,
        )
    }

    /**
     * Exponential backoff with full jitter.
     *
     * Jittered because the failure mode that matters is a warm instance dying
     * under load: without it, every client that failed together retries together
     * and re-creates the spike that took the instance down.
     */
    private fun backoffMs(attempt: Int): Long {
        // coerceAtLeast keeps the range non-empty when a caller configures a base
        // delay above the cap; nextLong would throw on an inverted range.
        val ceiling = (config.retryBaseDelayMs * 2.0.pow(attempt)).toLong()
            .coerceAtMost(MAX_BACKOFF_MS)
            .coerceAtLeast(config.retryBaseDelayMs)
        return random.nextLong(config.retryBaseDelayMs, ceiling + 1)
    }

    private companion object {
        const val API_KEY_HEADER = "X-Api-Key"
        const val ENROLL_PATH = "enroll"
        const val VERIFY_PATH = "verify"
        const val LIVENESS_SESSIONS_PATH = "livenessSessions"
        const val ACTIVE_LIVENESS_PROTOCOL = "active-liveness-v1"
        const val SDK_ID = "facecheck-kmp"
        const val HTTP_SERVER_ERROR = 500
        const val MAX_BACKOFF_MS = 8_000L
    }
}

@Serializable
private data class LivenessSessionRequest(
    val operation: String,
    val subjectId: String,
    val protocolVersion: String,
    val sdk: String,
    val locationContext: LocationContext,
    val requestedModelProfileId: String? = null,
)

@Serializable
private data class EvidenceManifestItem(
    val index: Int,
    val role: String,
    val width: Int,
    val height: Int,
    val sha256: String,
)

private fun CapturedEvidenceBundle.toManifestJson(): String =
    Json.encodeToString(
        images.mapIndexed { index, item ->
            EvidenceManifestItem(
                index = index,
                role = item.role.wire,
                width = item.jpeg.width,
                height = item.jpeg.height,
                sha256 = item.sha256 ?: sha256Hex(item.jpeg.bytes),
            )
        },
    )

private fun requireSession(session: LivenessSessionDescriptor, operation: String) {
    if (session.operation == operation && session.protocolVersion == "active-liveness-v1") return
    throw FaceCheckException(
        code = FaceCheckErrorCode.LIVENESS_SESSION_MISMATCH,
        message = "La sesión de liveness no coincide con esta solicitud.",
    )
}

/**
 * Attach an image part the backend will accept.
 *
 * Both headers are mandatory, not decoration: `read_image_upload` rejects any
 * part whose declared content type is not in its allow-list, and treats a part
 * with no filename as absent entirely — an upload missing either one comes back
 * as `MISSING_FILE` with a perfectly valid image sitting in the body.
 */
private fun FormBuilder.appendImage(field: String, bytes: ByteArray, filename: String = "$field.jpg") {
    append(
        key = field,
        value = bytes,
        headers = Headers.build {
            append(HttpHeaders.ContentType, "image/jpeg")
            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
        },
    )
}

/** Render a details value as a flat string, dropping nulls and nested objects. */
private fun JsonElement.asPlainString(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else content
    else -> null
}

/**
 * Classify a transport-layer exception without depending on engine types.
 *
 * Ktor's timeout and socket exceptions live in platform source sets, so common
 * code cannot name them. Matching on the class name is unlovely but it is the
 * only portable option, and getting it wrong only costs a slightly less precise
 * error code.
 */
private fun Exception.asTransportFailure(): FaceCheckException {
    val name = this::class.simpleName.orEmpty()
    val code = if (name.contains("Timeout", ignoreCase = true)) {
        FaceCheckErrorCode.TIMEOUT
    } else {
        FaceCheckErrorCode.NETWORK_ERROR
    }
    return FaceCheckException(code = code, cause = this)
}
