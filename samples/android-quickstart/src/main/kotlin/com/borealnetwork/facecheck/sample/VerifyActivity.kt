package com.borealnetwork.facecheck.sample

import android.Manifest
import android.content.res.ColorStateList
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.camera.AndroidCameraController
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.liveness.ChallengeMachine
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Runnable Android quickstart with a realistic, guided enrolment and verification flow. */
class VerifyActivity : ComponentActivity() {

    private lateinit var root: FrameLayout

    private var camera: AndroidCameraController? = null
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
                FaceCheckConfig(
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
            is ImmersiveScreen.SubjectSetup -> renderSubjectSetup(screen.operation, screen.validationMessage)
            is ImmersiveScreen.Outcome -> renderOutcome(screen)
            is ImmersiveScreen.Capture -> Unit
        }
    }

    private fun renderHome() {
        currentScreen = ImmersiveScreen.Home
        val column = screenColumn()
        column.addView(title("Prueba FaceCheck"))
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
            column.addView(status("Permisos aceptados. La ubicación se usa solo como requisito local; no enviamos coordenadas.", Color.rgb(23, 108, 60)))
            addSpace(column, 16)
            column.addView(primaryButton("Enrolar una persona") { openSubjectSetup(SampleOperation.ENROLL) })
            addSpace(column, 12)
            column.addView(secondaryButton("Verificar una identidad") { openSubjectSetup(SampleOperation.VERIFY) })
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

    private fun renderSubjectSetup(operation: SampleOperation, validationMessage: String? = null) {
        currentScreen = ImmersiveScreen.SubjectSetup(operation, validationMessage)
        val column = screenColumn()
        val isEnrollment = operation == SampleOperation.ENROLL
        column.addView(title(if (isEnrollment) "Enrolar una persona" else "Verificar una identidad"))
        column.addView(
            body(
                if (isEnrollment) {
                    "Elige el correo que identificará este rostro. La captura empieza en el siguiente paso."
                } else {
                    "Selecciona una persona enrolada por este sample o escribe su correo. La captura empieza en el siguiente paso."
                },
            ),
        )
        validationMessage?.let {
            addSpace(column, 12)
            column.addView(status(it, Color.rgb(177, 39, 39)))
        }
        addSpace(column, 20)

        val emailInput = EditText(this).apply {
            hint = "Correo de la persona"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        column.addView(emailInput, fullWidth())

        if (!isEnrollment) {
            addSpace(column, 20)
            column.addView(sectionTitle("Enroladas en este dispositivo"))
            val subjects = knownSubjects()
            if (subjects.isEmpty()) {
                column.addView(body("Todavía no hay rostros enrolados desde este teléfono."))
            } else {
                subjects.forEach { email ->
                    column.addView(secondaryButton(email) { emailInput.setText(email) })
                    addSpace(column, 8)
                }
            }
        }

        addSpace(column, 24)
        column.addView(primaryButton("Continuar a la cámara") {
            when (val next = ImmersiveSampleFlow.begin(operation, emailInput.text.toString())) {
                is ImmersiveScreen.Capture -> renderCapture(next)
                is ImmersiveScreen.SubjectSetup -> renderSubjectSetup(operation, next.validationMessage)
                else -> Unit
            }
        })
        addSpace(column, 12)
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
        val overlay = FaceGuideOverlay(this)
        frame.addView(preview, matchParent())
        frame.addView(overlay, matchParent())

        val cancelButton = secondaryButton("Cancelar") { cancelCapture(sessionId) }
        frame.addView(
            cancelButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = 36
                marginEnd = 28
            },
        )

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
        guidance.addView(instruction)
        guidance.addView(progress, LinearLayout.LayoutParams(MATCH, 12))
        frame.addView(
            guidance,
            FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        val loading = loadingOverlay(screen.operation)
        frame.addView(loading, matchParent())
        install(frame)

        val controller = AndroidCameraController(host = CameraHost(this))
        controller.attachPreview(preview)
        camera = controller
        val machine = newChallengeMachine(screen.operation)
        challengeJob = observeChallenge(
            machine = machine,
            sessionId = sessionId,
            operation = screen.operation,
            overlay = overlay,
            step = step,
            instruction = instruction,
            progress = progress,
            cancelButton = cancelButton,
            loading = loading,
        )
        captureJob = lifecycleScope.launch {
            var enrollmentFailed = false
            var outcome: ImmersiveScreen.Outcome? = null
            try {
                val succeeded = when (screen.operation) {
                    SampleOperation.ENROLL -> FaceCheck.enroll(
                        email = screen.email,
                        camera = controller,
                        machine = machine,
                    ).enrolled
                    SampleOperation.VERIFY -> FaceCheck.verify(
                        email = screen.email,
                        camera = controller,
                        machine = machine,
                    ).verified
                }
                if (succeeded && screen.operation == SampleOperation.ENROLL) {
                    rememberSubject(screen.email)
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
                if (screen.operation == SampleOperation.ENROLL) enrollmentFailed = true
                else outcome = ImmersiveScreen.Outcome(screen.operation, false, "${error.code}: ${error.message}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (sessionId == activeCaptureId) {
                    if (!enrollmentFailed) releaseCamera()
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
                    )
                    screen.operation == SampleOperation.ENROLL && outcome?.succeeded == true ->
                        renderEnrollmentComplete(screen.email)
                    outcome != null -> renderOutcome(checkNotNull(outcome))
                }
            }
        }
    }

    private fun observeChallenge(
        machine: ChallengeMachine,
        sessionId: Long,
        operation: SampleOperation,
        overlay: FaceGuideOverlay,
        step: TextView,
        instruction: TextView,
        progress: ProgressBar,
        cancelButton: Button,
        loading: View,
    ): Job = lifecycleScope.launch {
        machine.state.collect { state ->
            if (sessionId == activeCaptureId) {
                val presentation = CapturePresentation.from(
                    state = state,
                    finalizingInstruction = if (operation == SampleOperation.ENROLL) {
                        "Guardando enrolamiento…"
                    } else {
                        "Verificando identidad…"
                    },
                )
                overlay.render(presentation)
                step.text = presentation.stepLabel
                instruction.text = presentation.instruction
                progress.isIndeterminate = presentation.isFinalizing
                if (!presentation.isFinalizing) progress.progress = (presentation.ringProgress * 100).toInt()
                loading.visibility = if (presentation.isFinalizing) View.VISIBLE else View.GONE
                cancelButton.visibility = if (presentation.isFinalizing) View.INVISIBLE else View.VISIBLE
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

    private fun newChallengeMachine(operation: SampleOperation): ChallengeMachine = when (operation) {
        SampleOperation.ENROLL -> ChallengeMachine(
            challenges = EnrollmentSessionPolicy.challenges,
            config = EnrollmentSessionPolicy.livenessConfig,
        )
        SampleOperation.VERIFY -> FaceCheck.newChallengeMachine()
    }

    private fun loadingOverlay(operation: SampleOperation): View = LinearLayout(this).apply {
        val isEnrollment = operation == SampleOperation.ENROLL
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(72, 72, 72, 72)
        setBackgroundColor(Color.argb(235, 3, 10, 19))
        visibility = View.GONE
        addView(ProgressBar(this@VerifyActivity))
        addSpace(this, 24)
        addView(TextView(this@VerifyActivity).apply {
            text = if (isEnrollment) "Guardando enrolamiento…" else "Verificando identidad…"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@VerifyActivity).apply {
            text = if (isEnrollment) {
                "Tus tres pasos se completaron. Protegemos el registro antes de continuar."
            } else {
                "Completamos los pasos. Estamos protegiendo la verificación antes de continuar."
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 211, 219))
            setPadding(0, 16, 0, 0)
        })
    }

    private fun renderEnrollmentRetry(
        frame: FrameLayout,
        screen: ImmersiveScreen.Capture,
        attempt: EnrollmentAttempt,
        cancelButton: Button,
    ) {
        val retry = attempt.retry()
        cancelButton.visibility = View.INVISIBLE
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
            text = "No pudimos completar el enrolamiento. Asegúrate de que solo haya un rostro dentro del marco y completa los tres movimientos."
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

    private fun renderEnrollmentComplete(email: String) {
        currentScreen = ImmersiveScreen.Outcome(
            operation = SampleOperation.ENROLL,
            succeeded = true,
            message = successMessage(SampleOperation.ENROLL),
        )
        val column = screenColumn()
        column.gravity = Gravity.CENTER_HORIZONTAL
        addSpace(column, 72)
        column.addView(TextView(this).apply {
            text = "✓"
            textSize = 80f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 142, 92))
        }, fullWidth())
        addSpace(column, 12)
        column.addView(title("Enrolamiento completado"))
        column.addView(body("El rostro de $email quedó listo para una verificación desde este dispositivo."))
        addSpace(column, 32)
        column.addView(primaryButton("Verificar esta identidad") { openSubjectSetup(SampleOperation.VERIFY) })
        addSpace(column, 12)
        column.addView(secondaryButton("Volver al inicio") { renderHome() })
        installColumn(column)
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
        challengeJob?.cancel()
        challengeJob = null
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

    private fun knownSubjects(): List<String> = LocalSubjectDirectory.normalizedDistinct(
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SUBJECTS_KEY, "")
            .orEmpty()
            .lineSequence()
            .toList(),
    )

    private fun rememberSubject(email: String) {
        val remembered = LocalSubjectDirectory.remember(knownSubjects(), email)
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
