package com.borealnetwork.facecheck.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.borealnetwork.facecheck.FaceCheck
import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.camera.AndroidCameraController
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.liveness.ChallengeMachine
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Runnable Android quickstart: accept the security prerequisites, enroll a
 * reference face, then verify it with a fresh liveness session.
 *
 * The API key comes from the ignored root `local.properties`, typically
 * written by `facecheck init --stack android`; it is never committed here.
 */
class VerifyActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var emailInput: EditText
    private lateinit var acceptPermissionsButton: Button
    private lateinit var enrollButton: Button
    private lateinit var verifyButton: Button

    private var camera: AndroidCameraController? = null
    private var configured = false
    private var busy = false

    private val requestRequiredPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshReadiness()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        configureFaceCheck()
        refreshReadiness()

        acceptPermissionsButton.setOnClickListener {
            requestRequiredPermissions.launch(RequiredPermissions.forSdk(Build.VERSION.SDK_INT).toTypedArray())
        }
        enrollButton.setOnClickListener { start(Operation.ENROLL) }
        verifyButton.setOnClickListener { start(Operation.VERIFY) }
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshReadiness()
    }

    private fun configureFaceCheck() {
        if (BuildConfig.FACECHECK_API_KEY.isBlank()) return
        try {
            FaceCheck.initialize(
                FaceCheckConfig(
                    apiKey = BuildConfig.FACECHECK_API_KEY,
                    baseUrl = BASE_URL,
                ),
            )
            configured = true
        } catch (error: FaceCheckException) {
            statusView.text = "${error.code}: ${error.message}"
        }
    }

    private fun start(operation: Operation) {
        val blocking = blockingMessage()
        if (blocking != null) {
            statusView.text = blocking
            refreshReadiness()
            return
        }

        val email = emailInput.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            statusView.text = "Escribe un correo válido para enrolar o verificar."
            return
        }

        busy = true
        refreshReadiness()

        val controller = AndroidCameraController(host = CameraHost(this))
        controller.attachPreview(previewView)
        camera = controller
        val machine: ChallengeMachine = FaceCheck.newChallengeMachine()
        val stateJob = observeChallenge(machine)

        lifecycleScope.launch {
            statusView.text = if (operation == Operation.ENROLL) {
                "Sigue los retos para enrolar tu rostro"
            } else {
                "Sigue los retos para verificar tu rostro"
            }
            try {
                statusView.text = when (operation) {
                    Operation.ENROLL -> {
                        val result = FaceCheck.enroll(email = email, camera = controller, machine = machine)
                        if (result.enrolled) "Rostro enrolado. Ya puedes verificarlo." else "No se pudo enrolar el rostro."
                    }
                    Operation.VERIFY -> {
                        val result = FaceCheck.verify(email = email, camera = controller, machine = machine)
                        if (result.verified) "Verificado" else result.messageEs ?: "No coincide"
                    }
                }
            } catch (error: FaceCheckException) {
                statusView.text = "${error.code}: ${error.message}"
            } finally {
                stateJob.cancel()
                controller.close()
                if (camera === controller) camera = null
                busy = false
                refreshReadiness(keepStatus = true)
            }
        }
    }

    private fun observeChallenge(machine: ChallengeMachine): Job = lifecycleScope.launch {
        machine.state.collect { state ->
            if (busy) statusView.text = state.instructionEs
        }
    }

    private fun blockingMessage(): String? = SessionPreconditions.blockingMessage(
        apiKey = if (configured) BuildConfig.FACECHECK_API_KEY else "",
        sdkInt = Build.VERSION.SDK_INT,
        granted = grantedPermissions(),
    )

    private fun grantedPermissions(): Set<String> = RequiredPermissions.forSdk(Build.VERSION.SDK_INT)
        .filterTo(mutableSetOf()) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun refreshReadiness(keepStatus: Boolean = false) {
        val blocking = blockingMessage()
        val ready = blocking == null
        acceptPermissionsButton.isEnabled = configured && !busy && !ready
        enrollButton.isEnabled = ready && !busy
        verifyButton.isEnabled = ready && !busy
        if (!keepStatus && !busy) {
            statusView.text = blocking ?: "Permisos aceptados. Enrola un rostro o verifica uno existente."
        }
    }

    override fun onDestroy() {
        camera?.close()
        camera = null
        FaceCheck.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                MATCH,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            setPadding(48, 48, 48, 96)
            setBackgroundColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            text = "Preparando configuración…"
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        emailInput = EditText(this).apply {
            hint = "Correo de la persona"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        acceptPermissionsButton = Button(this).apply {
            text = "Aceptar permisos requeridos"
        }
        enrollButton = Button(this).apply { text = "Enrolar" }
        verifyButton = Button(this).apply { text = "Verificar" }
        column.addView(statusView)
        column.addView(emailInput)
        column.addView(acceptPermissionsButton)
        column.addView(enrollButton)
        column.addView(verifyButton)
        root.addView(previewView)
        root.addView(column)
        return root
    }

    private enum class Operation { ENROLL, VERIFY }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val BASE_URL = "https://us-central1-facecheck-mx.cloudfunctions.net"
    }
}
