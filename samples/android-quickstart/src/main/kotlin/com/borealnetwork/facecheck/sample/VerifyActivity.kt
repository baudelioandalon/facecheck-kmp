package com.borealnetwork.facecheck.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.borealnetwork.facecheck.FaceCheck
import com.borealnetwork.facecheck.FaceCheckConfig
import com.borealnetwork.facecheck.camera.AndroidCameraController
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.liveness.ChallengeMachine
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.launch

/**
 * El quickstart entero: pedir permiso de cámara, correr una verificación y
 * pintar la instrucción que la máquina de retos va emitiendo.
 *
 * Está escrito con vistas construidas a mano para que el sample no arrastre
 * Compose ni layouts XML — lo interesante son las tres llamadas al SDK, no la
 * UI. Se compila en CI: si la API cambia, este archivo deja de compilar y el
 * README deja de mentir.
 *
 * Antes de correrlo, pon tu API key en [API_KEY].
 */
class VerifyActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var startButton: Button

    private var camera: AndroidCameraController? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVerification() else statusView.text = "Sin permiso de cámara"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        FaceCheck.initialize(FaceCheckConfig(apiKey = API_KEY, baseUrl = BASE_URL))

        startButton.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startVerification() else requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startVerification() {
        startButton.isEnabled = false

        // El controlador es del que lo crea: se cierra en onDestroy.
        val controller = AndroidCameraController(host = CameraHost(this))
        controller.attachPreview(previewView)
        camera = controller

        // La máquina se construye antes de arrancar la sesión para poder
        // suscribirse a su estado y pintar la primera instrucción de inmediato.
        val machine: ChallengeMachine = FaceCheck.newChallengeMachine()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                machine.state.collect { statusView.text = it.instructionEs }
            }
        }

        lifecycleScope.launch {
            statusView.text = try {
                val result = FaceCheck.verify(
                    email = SUBJECT_EMAIL,
                    camera = controller,
                    machine = machine,
                )
                if (result.verified) "Verificado" else result.messageEs ?: "No coincide"
            } catch (e: FaceCheckException) {
                "${e.code}: ${e.message}"
            } finally {
                startButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        camera?.close()
        camera = null
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
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(48, 48, 48, 96)
        }
        statusView = TextView(this).apply { text = "Listo para verificar" }
        startButton = Button(this).apply { text = "Verificar" }
        column.addView(statusView)
        column.addView(startButton)
        root.addView(previewView)
        root.addView(column)
        return root
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Del portal. Una `lk_test_…` sirve para probar sin grants. */
        const val API_KEY = "lk_test_pon_aqui_tu_llave"
        const val BASE_URL = "https://us-central1-facecheck-mx.cloudfunctions.net"
        const val SUBJECT_EMAIL = "persona@ejemplo.com"
    }
}