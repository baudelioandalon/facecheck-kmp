package com.borealnetwork.facecheck.sample

import android.Manifest
import android.content.res.ColorStateList
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.borealnetwork.facecheck.FaceCheck
import com.borealnetwork.facecheck.SubjectId
import com.borealnetwork.facecheck.camera.AndroidCameraController
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.liveness.ActiveLivenessState
import com.borealnetwork.facecheck.model.FaceCheckException
import com.borealnetwork.facecheck.model.ModelProfileSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Runnable Android quickstart with a realistic, guided enrolment and verification flow. */
class VerifyActivity : ComponentActivity() {

    private lateinit var root: FrameLayout

    private var camera: AndroidCameraController? = null
    private var preflightJob: Job? = null
    private var challengeJob: Job? = null
    private var captureJob: Job? = null
    private var configured = false
    private var configurationError: String? = null
    private var busy = false
    private var activeCaptureId = 0L
    private var currentScreen: ImmersiveScreen = ImmersiveScreen.Home

    private val requestRequiredPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val blocking = blockingMessage()
        if (blocking == null) renderHome() else renderPermissionGate(blocking)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        configureFaceCheck()
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) renderCurrentNonCaptureScreen()
    }

    override fun onDestroy() {
        activeCaptureId += 1
        captureJob?.cancel()
        releaseCamera()
        FaceCheck.shutdown()
        super.onDestroy()
    }

    private fun configureFaceCheck() {
        if (BuildConfig.FACECHECK_API_KEY.isBlank()) return
        try {
            FaceCheck.initialize(
                sampleFaceCheckConfig(
                    apiKey = BuildConfig.FACECHECK_API_KEY,
                    baseUrl = BASE_URL,
                    livenessTimeoutMs = ENROLLMENT_LIVENESS_TIMEOUT_MS,
                ),
            )
            configured = true
        } catch (error: FaceCheckException) {
            configurationError = "${error.code}: ${error.message}"
        }
    }

    private fun renderCurrentNonCaptureScreen() {
        when (val screen = currentScreen) {
            ImmersiveScreen.Home -> renderHome()
            is ImmersiveScreen.PermissionGate -> renderPermissionGate(screen.message)
            is ImmersiveScreen.SubjectSetup -> renderSubjectSetup(
                operation = screen.operation,
                validationMessage = screen.validationMessage,
                subjectId = screen.subjectId,
            )
            ImmersiveScreen.VerificationDirectory -> renderVerificationDirectory()
            is ImmersiveScreen.CameraPreflight -> Unit
            is ImmersiveScreen.Outcome -> renderOutcome(screen)
            is ImmersiveScreen.Capture -> Unit
        }
    }

    private fun renderHome() {
        currentScreen = ImmersiveScreen.Home
        val column = screenColumn()
        column.addView(title("Prueba FaceCheck"))
        addSpace(column, 12)
        column.addView(
            environmentBadge(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(body("Primero confirma los permisos. Después podrás elegir enrolar una persona o verificar una identidad."))
        addSpace(column, 24)

        val blocking = blockingMessage()
        if (blocking != null) {
            column.addView(status(blocking, Color.rgb(168, 83, 19)))
            addSpace(column, 16)
            if (configured) {
                column.addView(primaryButton("Aceptar permisos requeridos") {
                    renderPermissionGate(blocking)
                })
            }
        } else {
            column.addView(status("Permisos aceptados. La ubicación se envía al servidor como señal de seguridad al iniciar sesión.", Color.rgb(23, 108, 60)))
            addSpace(column, 16)
            column.addView(primaryButton("Enrolar una persona") { openSubjectSetup(SampleOperation.ENROLL) })
            addSpace(column, 12)
            column.addView(secondaryButton("Verificar una identidad") { openVerificationDirectory() })
        }
        installColumn(column)
    }

    private fun renderPermissionGate(message: String) {
        currentScreen = ImmersiveScreen.PermissionGate(message)
        val column = screenColumn()
        column.addView(title("Antes de iniciar"))
        column.addView(body("FaceCheck necesita cámara, acceso a imágenes y ubicación antes de abrir una sesión. Esto evita iniciar una captura incompleta."))
        addSpace(column, 20)
        column.addView(status(message, Color.rgb(168, 83, 19)))
        addSpace(column, 24)
        if (configured) {
            column.addView(primaryButton("Aceptar y continuar") {
                requestRequiredPermissions.launch(RequiredPermissions.forSdk(Build.VERSION.SDK_INT).toTypedArray())
            })
            addSpace(column, 12)
        }
        column.addView(secondaryButton("Volver") { renderHome() })
        installColumn(column)
    }

    private fun openSubjectSetup(operation: SampleOperation) {
        blockingMessage()?.let {
            renderPermissionGate(it)
            return
        }
        renderSubjectSetup(operation)
    }

    private fun renderSubjectSetup(
        operation: SampleOperation,
        validationMessage: String? = null,
        subjectId: String = "",
    ) {
        currentScreen = ImmersiveScreen.SubjectSetup(operation, validationMessage, subjectId)
        val column = screenColumn()
        column.addView(title("Enrolar una persona"))
        column.addView(
            body("Escribe el ID de persona que identificará este rostro. La captura empieza en el siguiente paso."),
        )
        validationMessage?.let {
            addSpace(column, 12)
            column.addView(status(it, Color.rgb(177, 39, 39)))
        }
        addSpace(column, 20)

        val subjectIdInput = EditText(this).apply {
            hint = "ID de persona"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(subjectId)
        }
        column.addView(subjectIdInput, fullWidth())

        var selectedProfile: ModelProfileSummary? = null
        val profileStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(72, 84, 99))
            setPadding(0, 14, 0, 0)
            text = if (operation == SampleOperation.ENROLL) {
                "Cargando modelos permitidos…"
            } else {
                "La verificación usará el modelo guardado con esta persona."
            }
        }
        if (operation == SampleOperation.ENROLL) {
            column.addView(sectionTitle("Modelo de backend"))
            column.addView(profileStatus)
        }

        addSpace(column, 24)
        column.addView(secondaryButton("Generar ID aleatorio") {
            renderSubjectSetup(
                operation = operation,
                subjectId = SubjectId.generate(BuildConfig.FACECHECK_API_KEY),
            )
        })
        addSpace(column, 12)
        val continueButton = primaryButton("Continuar a la cámara") {
            when (val next = ImmersiveSampleFlow.beginAfterPreconditions(
                operation = operation,
                subjectId = subjectIdInput.text.toString(),
                blockingMessage = blockingMessage(),
            )) {
                is ImmersiveScreen.PermissionGate -> renderPermissionGate(next.message)
                is ImmersiveScreen.CameraPreflight -> {
                    val profile = selectedProfile
                    if (operation == SampleOperation.ENROLL && profile == null) return@primaryButton
                    renderCameraPreflight(next.copy(enrollmentProfile = profile))
                }
                is ImmersiveScreen.SubjectSetup -> renderSubjectSetup(
                    operation = operation,
                    validationMessage = next.validationMessage,
                    subjectId = next.subjectId,
                )
                else -> Unit
            }
        }.apply {
            isEnabled = operation != SampleOperation.ENROLL
        }
        column.addView(continueButton)
        addSpace(column, 12)
        column.addView(secondaryButton("Volver") { renderHome() })
        installColumn(column)

        if (operation == SampleOperation.ENROLL) {
            lifecycleScope.launch {
                runCatching { FaceCheck.enrollmentModelProfiles() }
                    .onSuccess { catalog ->
                        val profile = ModelProfileSelection.selectDefault(catalog)
                        selectedProfile = profile
                        if (profile == null) {
                            profileStatus.text =
                                "No hay modelos disponibles para este ambiente. En producción solo se muestran modelos comercialmente autorizados."
                            profileStatus.setTextColor(Color.rgb(177, 39, 39))
                            continueButton.isEnabled = false
                        } else {
                            profileStatus.text = ModelProfileSelection.label(profile)
                            profileStatus.setTextColor(Color.rgb(72, 84, 99))
                            continueButton.isEnabled = true
                        }
                    }
                    .onFailure { error ->
                        profileStatus.text = "No pudimos cargar los modelos: ${error.message}"
                        profileStatus.setTextColor(Color.rgb(177, 39, 39))
                        continueButton.isEnabled = false
                    }
            }
        }
    }

    private fun openVerificationDirectory() {
        blockingMessage()?.let {
            renderPermissionGate(it)
            return
        }
        renderVerificationDirectory()
    }

    private fun renderVerificationDirectory() {
        currentScreen = ImmersiveScreen.VerificationDirectory
        val column = screenColumn()
        column.addView(title("Verificar una identidad"))
        column.addView(
            body("Selecciona un ID de persona enrolado por este sample en este dispositivo."),
        )
        addSpace(column, 20)
        column.addView(sectionTitle("IDs enrolados en este dispositivo"))
        val subjects = knownSubjects()
        if (subjects.isEmpty()) {
            column.addView(body("Todavía no hay rostros enrolados desde este teléfono."))
        } else {
            subjects.forEach { subjectId ->
                addSpace(column, 8)
                column.addView(secondaryButton(subjectId) {
                    renderCameraPreflight(
                        ImmersiveScreen.CameraPreflight(SampleOperation.VERIFY, subjectId),
                    )
                })
            }
        }
        addSpace(column, 24)
        column.addView(secondaryButton("Volver") { renderHome() })
        installColumn(column)
    }

    private fun renderCapture(
        screen: ImmersiveScreen.Capture,
        enrollmentAttempt: EnrollmentAttempt = EnrollmentAttempt.first,
    ) {
        blockingMessage()?.let {
            renderPermissionGate(it)
            return
        }
        currentScreen = screen
        busy = true
        val sessionId = ++activeCaptureId
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this)
        val guideGeometry = FaceGuideGeometry()
        val overlay = FaceGuideOverlay(this, guideGeometry)
        frame.addView(preview, matchParent())
        frame.addView(overlay, matchParent())

        frame.addView(
            environmentBadge(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = 44
                marginStart = 28
            },
        )

        val cancelButton = secondaryButton("Cancelar") { cancelCapture(sessionId) }
        addCameraTopActions(frame, overlay, cancelButton)

        val guidance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 56)
            setBackgroundColor(Color.argb(224, 4, 12, 23))
        }
        val step = TextView(this).apply {
            setTextColor(Color.rgb(155, 237, 203))
            textSize = 14f
        }
        val attempt = TextView(this).apply {
            text = enrollmentAttempt.label
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 13f
            visibility = if (screen.operation == SampleOperation.ENROLL) View.VISIBLE else View.GONE
            setPadding(0, 8, 0, 0)
        }
        val timer = TextView(this).apply {
            text = "Expira en --:--"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        val instruction = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 25f
            setPadding(0, 8, 0, 0)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(117, 224, 184))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(52, 72, 80))
            setPadding(0, 24, 0, 0)
        }
        guidance.addView(step)
        guidance.addView(attempt)
        guidance.addView(timer)
        guidance.addView(instruction)
        guidance.addView(progress, LinearLayout.LayoutParams(MATCH, 12))
        frame.addView(
            guidance,
            FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        val loading = loadingOverlay(screen.operation)
        frame.addView(loading.root, matchParent())
        install(frame)

        val controller = AndroidCameraController(host = CameraHost(this))
        controller.attachPreview(preview)
        val previewFaceGuide = PreviewFaceGuide(guideGeometry::contains)
        controller.setPreviewFaceGuide(previewFaceGuide::contains)
        camera = controller
        val challengeFeedback = ChallengeCompletionFeedback()
        captureJob = lifecycleScope.launch {
            var enrollmentFailed = false
            var enrollmentFailureMessage: String? = null
            var outcome: ImmersiveScreen.Outcome? = null
            var timerJob: Job? = null
            try {
                instruction.text = "Creando sesión segura…"
                loading.visibility = View.VISIBLE
                val location = CurrentLocationProvider(this@VerifyActivity).current()
                val succeeded = when (screen.operation) {
                    SampleOperation.ENROLL -> {
                        val profile = checkNotNull(screen.enrollmentProfile) {
                            "enrollment profile is required before capture"
                        }
                        val session = FaceCheck.prepareEnrollment(
                            subjectId = screen.subjectId,
                            modelProfileId = profile.id,
                            location = location,
                        )
                        timerJob = startSessionCountdown(session.expiresAt, timer, sessionId)
                        challengeJob = observeActiveSession(
                            sessionState = session.state,
                            sessionId = sessionId,
                            operation = screen.operation,
                            overlay = overlay,
                            step = step,
                            instruction = instruction,
                            progress = progress,
                            cancelButton = cancelButton,
                            loading = loading,
                            challengeFeedback = challengeFeedback,
                        )
                        session.run(
                            camera = controller,
                        ).enrolled
                    }
                    SampleOperation.VERIFY -> {
                        val session = FaceCheck.prepareVerification(
                            subjectId = screen.subjectId,
                            location = location,
                        )
                        timerJob = startSessionCountdown(session.expiresAt, timer, sessionId)
                        challengeJob = observeActiveSession(
                            sessionState = session.state,
                            sessionId = sessionId,
                            operation = screen.operation,
                            overlay = overlay,
                            step = step,
                            instruction = instruction,
                            progress = progress,
                            cancelButton = cancelButton,
                            loading = loading,
                            challengeFeedback = challengeFeedback,
                        )
                        session.run(
                            camera = controller,
                        ).verified
                    }
                }
                if (succeeded && screen.operation == SampleOperation.ENROLL) {
                    rememberSubject(screen.subjectId)
                }
                if (!succeeded && screen.operation == SampleOperation.ENROLL) {
                    enrollmentFailed = true
                } else {
                    outcome = ImmersiveScreen.Outcome(
                        operation = screen.operation,
                        succeeded = succeeded,
                        message = if (succeeded) successMessage(screen.operation) else failureMessage(screen.operation),
                    )
                }
            } catch (error: FaceCheckException) {
                if (screen.operation == SampleOperation.ENROLL) {
                    enrollmentFailed = true
                    enrollmentFailureMessage = "${error.code}: ${error.message}"
                } else {
                    outcome = ImmersiveScreen.Outcome(screen.operation, false, "${error.code}: ${error.message}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = CaptureFailurePresentation.fromUnexpected(error)
                if (screen.operation == SampleOperation.ENROLL) {
                    enrollmentFailed = true
                    enrollmentFailureMessage = message
                } else {
                    outcome = ImmersiveScreen.Outcome(screen.operation, false, message)
                }
            } finally {
                timerJob?.cancel()
                if (sessionId == activeCaptureId) {
                    if (screen.operation == SampleOperation.VERIFY) releaseCamera()
                    busy = false
                }
            }
            if (sessionId == activeCaptureId) {
                when {
                    enrollmentFailed -> renderEnrollmentRetry(
                        frame = frame,
                        screen = screen,
                        attempt = enrollmentAttempt,
                        cancelButton = cancelButton,
                        loading = loading,
                        guidance = guidance,
                        message = enrollmentFailureMessage,
                    )
                    screen.operation == SampleOperation.ENROLL && outcome?.succeeded == true ->
                        renderEnrollmentComplete(frame, loading, guidance, screen.subjectId)
                    outcome != null -> renderOutcome(checkNotNull(outcome))
                }
            }
        }
    }

    private fun renderCameraPreflight(screen: ImmersiveScreen.CameraPreflight) {
        blockingMessage()?.let {
            renderPermissionGate(it)
            return
        }
        currentScreen = screen
        busy = true
        val sessionId = ++activeCaptureId
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this)
        val guideGeometry = FaceGuideGeometry()
        val overlay = FaceGuideOverlay(this, guideGeometry)
        frame.addView(preview, matchParent())
        frame.addView(overlay, matchParent())
        overlay.render(CapturePresentation("Coloca tu rostro dentro del óvalo", "Alineando rostro", 0f))

        frame.addView(
            environmentBadge(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = 44
                marginStart = 28
            },
        )
        val cancelButton = secondaryButton("Cancelar") { cancelCapture(sessionId) }
        addCameraTopActions(frame, overlay, cancelButton)

        val guidance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 56)
            setBackgroundColor(Color.argb(224, 4, 12, 23))
        }
        val step = TextView(this).apply {
            text = "Alineando rostro"
            textSize = 14f
            setTextColor(Color.rgb(155, 237, 203))
        }
        val instruction = TextView(this).apply {
            text = "Coloca tu rostro dentro del óvalo"
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 0)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(117, 224, 184))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(52, 72, 80))
            setPadding(0, 24, 0, 0)
        }
        var verificationReady = false
        val startButton = primaryButton(
            if (screen.operation == SampleOperation.ENROLL) "Empezar enrolamiento" else "Empezar verificación",
        ) {
            if (sessionId != activeCaptureId || !verificationReady) return@primaryButton
            activeCaptureId += 1
            releaseCamera()
            renderCapture(
                ImmersiveScreen.Capture(
                    operation = screen.operation,
                    subjectId = screen.subjectId,
                    enrollmentProfile = screen.enrollmentProfile,
                ),
            )
        }.apply { isEnabled = false }
        guidance.addView(step)
        guidance.addView(instruction)
        guidance.addView(progress, LinearLayout.LayoutParams(MATCH, 12))
        guidance.addView(startButton, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        frame.addView(guidance, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        install(frame)

        val controller = AndroidCameraController(host = CameraHost(this))
        controller.attachPreview(preview)
        controller.setPreviewFaceGuide(PreviewFaceGuide(guideGeometry::contains)::contains)
        camera = controller
        val readiness = CameraPreflightReadiness()
        controller.start()
        preflightJob = lifecycleScope.launch {
            try {
                controller.frames.collect { faceFrame ->
                    if (sessionId != activeCaptureId) return@collect
                    val state = readiness.onFrame(faceFrame)
                    step.text = state.stepLabel
                    instruction.text = state.instruction
                    progress.progress = (state.progress * 100).toInt()
                    overlay.render(CapturePresentation(state.instruction, state.stepLabel, state.progress))
                    verificationReady = state.isReady
                    startButton.isEnabled = state.isReady
                }
            } catch (error: FaceCheckException) {
                if (sessionId == activeCaptureId) {
                    busy = false
                    releaseCamera()
                    renderOutcome(ImmersiveScreen.Outcome(SampleOperation.VERIFY, false, "${error.code}: ${error.message}"))
                }
            }
        }
    }

    private fun observeActiveSession(
        sessionState: StateFlow<ActiveLivenessState>,
        sessionId: Long,
        operation: SampleOperation,
        overlay: FaceGuideOverlay,
        step: TextView,
        instruction: TextView,
        progress: ProgressBar,
        cancelButton: Button,
        loading: LoadingOverlay,
        challengeFeedback: ChallengeCompletionFeedback,
    ): Job = lifecycleScope.launch {
        sessionState.collect { activeState ->
            if (sessionId == activeCaptureId) {
                val finalizingInstruction = if (operation == SampleOperation.ENROLL) {
                    "Guardando enrolamiento…"
                } else {
                    "Verificando identidad…"
                }
                val presentation = when (activeState) {
                    is ActiveLivenessState.Capturing -> CapturePresentation.from(
                        state = activeState.presentation,
                        finalizingInstruction = finalizingInstruction,
                    )
                    ActiveLivenessState.Uploading,
                    ActiveLivenessState.Processing,
                    ActiveLivenessState.Completed -> CapturePresentation(
                        instruction = finalizingInstruction,
                        stepLabel = "Pasos completados",
                        progress = 1f,
                        isFinalizing = true,
                    )
                    ActiveLivenessState.Ready -> CapturePresentation(
                        instruction = "Preparando sesión segura…",
                        stepLabel = "Preparando",
                        progress = 0f,
                    )
                    ActiveLivenessState.Cancelled -> CapturePresentation(
                        instruction = "Cancelado",
                        stepLabel = "Sesión cancelada",
                        progress = 0f,
                    )
                    is ActiveLivenessState.Failed -> CapturePresentation(
                        instruction = activeState.error.message,
                        stepLabel = "No fue posible completar la sesión",
                        progress = 0f,
                    )
                }
                overlay.render(presentation)
                step.text = presentation.stepLabel
                instruction.text = presentation.instruction
                progress.isIndeterminate = presentation.isFinalizing
                if (!presentation.isFinalizing) progress.progress = (presentation.ringProgress * 100).toInt()
                loading.render(
                    if (presentation.isFinalizing) {
                        CaptureLoadingPresentation.finalizing(operation)
                    } else {
                        CaptureLoadingPresentation.preparing(operation)
                    },
                )
                loading.visibility = if (presentation.isFinalizing) View.VISIBLE else View.GONE
                cancelButton.visibility = if (presentation.isFinalizing) View.INVISIBLE else View.VISIBLE
                val livenessState = (activeState as? ActiveLivenessState.Capturing)?.presentation
                if (livenessState != null && challengeFeedback.consume(livenessState)) {
                    overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    overlay.playSoundEffect(SoundEffectConstants.CLICK)
                }
            }
        }
    }

    private fun cancelCapture(sessionId: Long) {
        if (sessionId != activeCaptureId) return
        activeCaptureId += 1
        captureJob?.cancel()
        releaseCamera()
        busy = false
        renderHome()
    }

    private fun startSessionCountdown(
        expiresAt: Instant,
        target: TextView,
        sessionId: Long,
    ): Job = lifecycleScope.launch {
        while (sessionId == activeCaptureId) {
            target.text = "Expira en ${ActiveSessionPresentation.countdownLabel(expiresAt, Clock.System.now())}"
            delay(1_000)
        }
    }

    private data class LoadingOverlay(
        val root: View,
        val title: TextView,
        val body: TextView,
    ) {
        var visibility: Int
            get() = root.visibility
            set(value) {
                root.visibility = value
            }

        fun render(presentation: CaptureLoadingPresentation) {
            title.text = presentation.title
            body.text = presentation.body
        }
    }

    private fun loadingOverlay(operation: SampleOperation): LoadingOverlay {
        val title = TextView(this).apply {
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val body = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 211, 219))
            setPadding(0, 16, 0, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(72, 72, 72, 72)
            setBackgroundColor(Color.argb(235, 3, 10, 19))
            visibility = View.GONE
            addView(ProgressBar(this@VerifyActivity))
            addSpace(this, 24)
            addView(title)
            addView(body)
        }
        return LoadingOverlay(root, title, body).apply {
            render(CaptureLoadingPresentation.preparing(operation))
        }
    }

    private fun renderEnrollmentRetry(
        frame: FrameLayout,
        screen: ImmersiveScreen.Capture,
        attempt: EnrollmentAttempt,
        cancelButton: Button,
        loading: LoadingOverlay,
        guidance: View,
        message: String?,
    ) {
        val presentation = EnrollmentTerminalPresentation.from(attempt)
        val retry = presentation.nextAttempt
        cancelButton.visibility = View.INVISIBLE
        loading.visibility = if (presentation.showsLoading) View.VISIBLE else View.GONE
        guidance.visibility = View.GONE
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
            setBackgroundColor(Color.rgb(18, 27, 41))
        }
        card.addView(TextView(this).apply {
            text = if (retry == null) "Se agotaron los intentos" else "Volvamos a intentarlo"
            textSize = 25f
            setTextColor(Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = message
                ?: "No pudimos completar el enrolamiento. Asegúrate de que solo haya un rostro dentro del marco y completa los tres movimientos."
            textSize = 16f
            setTextColor(Color.rgb(255, 188, 184))
            setPadding(0, 14, 0, 0)
        })
        card.addView(TextView(this).apply {
            text = attempt.label
            textSize = 14f
            setTextColor(Color.rgb(190, 211, 219))
            setPadding(0, 16, 0, 0)
        })
        retry?.let { next ->
            card.addView(primaryButton("Volver a intentar") {
                releaseCamera()
                renderCapture(screen, next)
            }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        }
        card.addView(secondaryButton("Aceptar") {
            activeCaptureId += 1
            releaseCamera()
            renderHome()
        }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
        frame.addView(
            card,
            FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    private fun renderEnrollmentComplete(
        frame: FrameLayout,
        loading: LoadingOverlay,
        guidance: View,
        subjectId: String,
    ) {
        currentScreen = ImmersiveScreen.Outcome(
            operation = SampleOperation.ENROLL,
            succeeded = true,
            message = successMessage(SampleOperation.ENROLL),
        )
        loading.visibility = View.GONE
        guidance.visibility = View.GONE
        releaseCamera()

        frame.addView(View(this).apply {
            setBackgroundColor(Color.argb(176, 3, 10, 19))
        }, matchParent())
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(249, 250, 252))
        }
        dialog.addView(TextView(this).apply {
            text = "✓"
            textSize = 80f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 142, 92))
        }, fullWidth())
        addSpace(dialog, 12)
        dialog.addView(title("Enrolamiento completado"), fullWidth())
        dialog.addView(body("El rostro con ID $subjectId quedó listo para una verificación desde este dispositivo."))
        addSpace(dialog, 32)
        dialog.addView(primaryButton("Verificar esta identidad") { renderVerificationDirectory() })
        addSpace(dialog, 12)
        dialog.addView(secondaryButton("Volver al inicio") { renderHome() })
        frame.addView(
            dialog,
            FrameLayout.LayoutParams(
                MATCH,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                marginStart = 40
                marginEnd = 40
            },
        )
    }

    private fun renderOutcome(screen: ImmersiveScreen.Outcome) {
        currentScreen = screen
        val column = screenColumn()
        val heading = when {
            screen.succeeded && screen.operation == SampleOperation.ENROLL -> "Rostro enrolado"
            screen.succeeded -> "Identidad verificada"
            else -> "No fue posible completar la sesión"
        }
        column.addView(title(heading))
        column.addView(status(screen.message, if (screen.succeeded) Color.rgb(23, 108, 60) else Color.rgb(177, 39, 39)))
        addSpace(column, 28)
        column.addView(primaryButton("Volver al inicio") { renderHome() })
        installColumn(column)
    }

    private fun successMessage(operation: SampleOperation): String = when (operation) {
        SampleOperation.ENROLL -> "El rostro quedó enrolado y estará disponible para verificar desde este teléfono."
        SampleOperation.VERIFY -> "La identidad coincide con el rostro enrolado."
    }

    private fun failureMessage(operation: SampleOperation): String = when (operation) {
        SampleOperation.ENROLL -> "No se pudo enrolar el rostro. Inténtalo de nuevo con mejor luz y el rostro dentro del marco."
        SampleOperation.VERIFY -> "El rostro no coincide con la persona seleccionada."
    }

    private fun releaseCamera() {
        preflightJob?.cancel()
        preflightJob = null
        challengeJob?.cancel()
        challengeJob = null
        camera?.setPreviewFaceGuide(null)
        camera?.close()
        camera = null
        captureJob = null
    }

    private fun blockingMessage(): String? = configurationError ?: SessionPreconditions.blockingMessage(
        apiKey = if (configured) BuildConfig.FACECHECK_API_KEY else "",
        sdkInt = Build.VERSION.SDK_INT,
        granted = grantedPermissions(),
    )

    private fun grantedPermissions(): Set<String> = RequiredPermissions.forSdk(Build.VERSION.SDK_INT)
        .filterTo(mutableSetOf()) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun knownSubjects(): List<String> {
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return LocalSubjectDirectory.readAndMigrate(
            readStoredSubjects = { preferences.getString(SUBJECTS_KEY, "").orEmpty() },
            persistSubjects = { normalizedSubjects ->
                preferences.edit().putString(SUBJECTS_KEY, normalizedSubjects).apply()
            },
        )
    }

    private fun rememberSubject(subjectId: String) {
        val remembered = LocalSubjectDirectory.remember(knownSubjects(), subjectId)
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SUBJECTS_KEY, remembered.joinToString("\n"))
            .apply()
    }

    private fun install(content: View) {
        root.removeAllViews()
        root.addView(content, matchParent())
    }

    private fun installColumn(column: LinearLayout) {
        install(column.tag as View)
    }

    private fun screenColumn(): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 56)
            setBackgroundColor(Color.rgb(249, 250, 252))
        }
        return column.also {
            val scroll = ScrollView(this).apply { addView(it, matchParent()) }
            it.tag = scroll
        }
    }

    private fun title(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 30f
        setTextColor(Color.rgb(20, 28, 43))
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(55, 69, 86))
    }

    private fun body(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(Color.rgb(72, 84, 99))
        setPadding(0, 16, 0, 0)
    }

    private fun status(value: String, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(color)
    }

    private fun environmentBadge(): TextView {
        val environment = SampleEnvironment.fromApiKey(BuildConfig.FACECHECK_API_KEY)
        val accent = if (environment == SampleEnvironment.PRODUCTION) {
            Color.rgb(46, 104, 207)
        } else {
            Color.rgb(38, 128, 91)
        }
        return TextView(this).apply {
            text = "Ambiente: ${environment.label}"
            textSize = 12f
            setTextColor(accent)
            setPadding(18, 8, 18, 8)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(1, Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
    }

    private fun addCameraTopActions(
        frame: FrameLayout,
        overlay: FaceGuideOverlay,
        cancelButton: Button,
    ) {
        var lighting = FaceGuideLighting.Normal
        lateinit var flashButton: Button

        fun applyLighting() {
            overlay.setLighting(lighting)
            flashButton.text = lighting.buttonLabel
            flashButton.contentDescription = lighting.contentDescription
            val accent = if (lighting.requiresDarkButtonText) Color.rgb(22, 28, 34) else Color.rgb(155, 237, 203)
            flashButton.setTextColor(accent)
            flashButton.backgroundTintList = ColorStateList.valueOf(
                if (lighting == FaceGuideLighting.LowLight) {
                    Color.argb(220, 255, 255, 255)
                } else {
                    Color.argb(32, 117, 224, 184)
                },
            )
        }

        flashButton = secondaryButton(lighting.buttonLabel) {
            lighting = lighting.toggle()
            applyLighting()
        }.apply {
            textSize = 22f
            minWidth = 128
            minimumWidth = 128
            setPadding(24, 0, 24, 0)
        }
        applyLighting()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                flashButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                cancelButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = 12
                },
            )
        }
        frame.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 36
                marginEnd = 28
            },
        )
    }

    private fun primaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun addSpace(parent: LinearLayout, height: Int) {
        parent.addView(Space(this), LinearLayout.LayoutParams(1, height))
    }

    private fun fullWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchParent(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(MATCH, MATCH)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val BASE_URL = "https://us-central1-facecheck-mx.cloudfunctions.net"
        const val ENROLLMENT_LIVENESS_TIMEOUT_MS = 120_000L
        const val PREFERENCES = "facecheck_sample"
        const val SUBJECTS_KEY = "enrolled_subjects"
    }
}
