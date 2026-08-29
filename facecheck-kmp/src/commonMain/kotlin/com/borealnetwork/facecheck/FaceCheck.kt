package com.borealnetwork.facecheck

import com.borealnetwork.facecheck.camera.CameraController
import com.borealnetwork.facecheck.liveness.ChallengeMachine
import com.borealnetwork.facecheck.liveness.ChallengePlan
import com.borealnetwork.facecheck.liveness.EnrollmentSession
import com.borealnetwork.facecheck.liveness.VerificationSession
import com.borealnetwork.facecheck.liveness.runLivenessSession
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.FaceCheckBranding
import com.borealnetwork.facecheck.model.FaceCheckBrandingOverride
import com.borealnetwork.facecheck.model.DocumentCapturePolicy
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.IdentityDocument
import com.borealnetwork.facecheck.model.IneFrontValidationResult
import com.borealnetwork.facecheck.model.LocationContext
import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.VerifyResult
import com.borealnetwork.facecheck.net.FaceCheckBackend
import com.borealnetwork.facecheck.net.FaceCheckApi
import kotlin.random.Random

/**
 * The public entry point.
 *
 * ```kotlin
 * FaceCheck.initialize(
 *     FaceCheckConfig(apiKey = "lk_test_…", baseUrl = "https://…"),
 * )
 *
 * val machine = FaceCheck.newChallengeMachine()   // observe machine.state in the UI
 * val result = FaceCheck.verify(
 *     subjectId = "sub_ABCDEFGHIJ_abcdefghijklmnopqrstuv",
 *     camera = createCameraController(host),
 *     machine = machine,
 * )
 * ```
 *
 * ### What the SDK decides, and what it does not
 *
 * It decides nothing. It coaches the user through a liveness session, captures
 * one good photo, and posts it. Whether that photo belongs to the enrolled
 * person is decided by the backend, against a threshold this code never sees.
 * A [VerifyResult] with `verified = true` is the backend's answer; anything the
 * on-device machine concluded on the way there is presentation.
 *
 * ### Threading
 *
 * [initialize] is expected once, from app startup, before any other call.
 * [enroll] and [verify] are suspending and safe to call from any dispatcher;
 * each runs its own independent session, though sharing one [CameraController]
 * between concurrent sessions is not.
 */
object FaceCheck {

    private var activeConfig: FaceCheckConfig? = null
    private var api: FaceCheckBackend? = null

    /** The configuration in force, or null before [initialize]. */
    val config: FaceCheckConfig?
        get() = activeConfig

    val isInitialized: Boolean
        get() = activeConfig != null

    /**
     * Install the configuration. Idempotent for an identical config.
     *
     * Calling it again with a *different* config throws instead of silently
     * re-pointing the SDK. Two configs in one process means two API keys, and
     * therefore two tenants or two modes: whichever call happened to run last
     * would decide whose data an enrollment landed in, and the resulting bug
     * looks like data corruption rather than a misconfiguration. Better to fail
     * at startup, loudly, on the developer's machine.
     *
     * @throws FaceCheckException [FaceCheckErrorCode.INVALID_CONFIG] if already
     *   initialized with different settings.
     */
    fun initialize(config: FaceCheckConfig) {
        val current = activeConfig
        if (current != null) {
            if (current == config) return
            throw FaceCheckException(
                code = FaceCheckErrorCode.INVALID_CONFIG,
                message = "FaceCheck ya fue inicializado con una configuración distinta. " +
                    "Llama a initialize() una sola vez, al arrancar la aplicación.",
            )
        }
        activeConfig = config
        api = FaceCheckApi(config)
        FaceCheckLogger.level = config.logLevel
        FaceCheckLogger.info { "FaceCheck initialized" }
    }

    internal fun initializeForTests(config: FaceCheckConfig, backend: FaceCheckBackend) {
        shutdown()
        activeConfig = config
        api = backend
        FaceCheckLogger.level = config.logLevel
    }

    /**
     * Release the HTTP client and forget the configuration.
     *
     * Mostly for tests and for apps that tear the SDK down between user
     * sessions. After this, [initialize] accepts a new config.
     */
    fun shutdown() {
        api?.close()
        api = null
        activeConfig = null
    }

    /**
     * A machine for one session, with a freshly randomised challenge plan.
     *
     * Built by the caller rather than internally so the UI can subscribe to
     * [ChallengeMachine.state] *before* the session starts and render the very
     * first instruction, instead of showing an empty screen until the first
     * frame arrives.
     */
    fun newChallengeMachine(random: Random = Random.Default): ChallengeMachine {
        val config = requireConfig()
        return ChallengeMachine(
            challenges = ChallengePlan.random(config.challengeCount, random),
            config = config.liveness,
        )
    }

    suspend fun enrollmentModelProfiles(): ModelProfileCatalog =
        requireApi().getEnrollmentModelProfiles()

    /**
     * Reads the tenant identity used by FaceCheck UI.
     *
     * The canonical response is cached only in memory. [override] wins over the
     * optional config override for this call and changes only the effective
     * color palette; it never writes to the tenant or replaces name/icon/message.
     */
    suspend fun branding(
        refresh: Boolean = false,
        override: FaceCheckBrandingOverride? = null,
    ): FaceCheckBranding = requireApi().getBranding(refresh, override)

    suspend fun prepareEnrollment(
        subjectId: String,
        modelProfileId: String,
        documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        location: LocationContext,
    ): EnrollmentSession {
        val config = requireConfig()
        val descriptor = requireApi().createLivenessSession(
            operation = "enroll",
            subjectId = subjectId,
            requestedModelProfileId = modelProfileId,
            requestedDocumentPolicy = documentPolicy,
            location = location,
        )
        return EnrollmentSession(descriptor, requireApi(), config)
    }

    suspend fun prepareVerification(
        subjectId: String,
        location: LocationContext,
    ): VerificationSession {
        val config = requireConfig()
        val descriptor = requireApi().createLivenessSession(
            operation = "verify",
            subjectId = subjectId,
            requestedModelProfileId = null,
            requestedDocumentPolicy = DocumentCapturePolicy.FACE_ONLY,
            location = location,
        )
        return VerificationSession(descriptor, requireApi(), config)
    }

    /**
     * Register [subjectId]'s reference face: run a liveness session, then upload.
     *
     * @param camera supplies frames and the still; see [CameraController].
     * @param machine the session to drive; observe its state for the UI.
     * @param grant a short-lived token signed by the integrator's own **backend**
     *   authorising this subject to be enrolled. Required for `lk_live_` keys
     *   and optional for `lk_test_` ones, because the API key ships inside the
     *   app and so proves nothing about who the caller is: without a grant,
     *   anyone who extracts it could bind their face to someone else's subject.
     *   The signing secret must never reach the device. See
     *   <https://facecheck.borealnetwork.org/docs/grants> for how to mint one.
     * @param overwrite replace an existing enrollment for this subject. Without
     *   it an already-enrolled subject fails with
     *   [SUBJECT_ALREADY_ENROLLED][FaceCheckErrorCode.SUBJECT_ALREADY_ENROLLED]
     *   rather than being silently replaced. With it, the backend still demands
     *   that the new selfie match the stored template — see [FaceCheckConfig].
     * @param ine an optional photo of the person's ID, JPEG-encoded, enabling
     *   later verification against the credential portrait.
     *
     * @throws FaceCheckException on a failed liveness session or a rejected request.
     */
    suspend fun enroll(
        subjectId: String,
        camera: CameraController,
        machine: ChallengeMachine = newChallengeMachine(),
        grant: String? = null,
        overwrite: Boolean = false,
        ine: ByteArray? = null,
    ): EnrollResult {
        val config = requireConfig()
        val capture = runLivenessSession(camera, machine, config.livenessTimeoutMs)
        return requireApi().enroll(
            subjectId = subjectId,
            selfie = capture.still.bytes,
            ine = ine,
            grant = grant,
            overwrite = overwrite,
        )
    }

    /**
     * Match a fresh selfie for [subjectId] against what is stored.
     *
     * @param compareWith which template to match. The backend may raise this to
     *   a stricter comparison but never lowers it; see [CompareWith].
     *
     * @throws FaceCheckException on a failed liveness session or a rejected
     *   request. A face that simply does not match is **not** an exception: it
     *   comes back as [VerifyResult] with `verified = false` and a reason.
     */
    suspend fun verify(
        subjectId: String,
        camera: CameraController,
        machine: ChallengeMachine = newChallengeMachine(),
        compareWith: CompareWith = CompareWith.ENROLLMENT,
    ): VerifyResult {
        val config = requireConfig()
        val capture = runLivenessSession(camera, machine, config.livenessTimeoutMs)
        return requireApi().verify(
            subjectId = subjectId,
            selfie = capture.still.bytes,
            compareWith = compareWith,
        )
    }

    /**
     * Attach the front/back INE capture to an already enrolled subject.
     *
     * The backend stores an audit trail for document uploads, so callers must
     * collect a fresh [location] immediately before sending the document. If
     * location permission is missing or disabled, block before opening the
     * camera instead of attempting an upload that the server will reject.
     */
    suspend fun attachIdentityDocument(
        subjectId: String,
        document: IdentityDocument,
        location: LocationContext,
        grant: String? = null,
    ): EnrollResult = requireApi().attachIdentityDocument(
        subjectId = subjectId,
        document = document,
        location = location,
        grant = grant,
    )

    suspend fun validateIneFront(
        subjectId: String,
        front: ByteArray,
    ): IneFrontValidationResult = requireApi().validateIneFront(
        subjectId = subjectId,
        front = front,
    )

    private fun requireConfig(): FaceCheckConfig = activeConfig
        ?: throw FaceCheckException(FaceCheckErrorCode.NOT_INITIALIZED)

    private fun requireApi(): FaceCheckBackend = api
        ?: throw FaceCheckException(FaceCheckErrorCode.NOT_INITIALIZED)
}
