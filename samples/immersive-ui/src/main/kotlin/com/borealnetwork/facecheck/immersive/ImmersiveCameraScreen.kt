package com.borealnetwork.facecheck.immersive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.borealnetwork.facecheck.FaceCheck
import com.borealnetwork.facecheck.FaceCheckLogger
import com.borealnetwork.facecheck.camera.AndroidCameraController
import com.borealnetwork.facecheck.camera.CameraHost
import com.borealnetwork.facecheck.camera.CameraOptions
import com.borealnetwork.facecheck.camera.createCameraController
import com.borealnetwork.facecheck.liveness.ActiveLivenessState
import com.borealnetwork.facecheck.liveness.FaceFrame
import com.borealnetwork.facecheck.liveness.LivenessState
import com.borealnetwork.facecheck.liveness.PositioningHint
import com.borealnetwork.facecheck.location.AndroidLocationContextProvider
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.compose.runtime.collectAsState

/** What the last enrollment attempt produced. */
private sealed interface Outcome {
    data class Enrolled(val result: EnrollResult) : Outcome

    /** The SDK rejected it: a liveness failure, a network error or backend code. */
    data class Rejected(val error: FaceCheckException) : Outcome

    /** Anything else, which in a demo means a bug worth seeing on screen. */
    data class Crashed(val error: Throwable) : Outcome
}

private val Amber = Color(0xFFFBBF24)

@Composable
fun ImmersiveCameraScreen(
    activity: ComponentActivity,
    settings: ImmersiveSettings,
    onBack: () -> Unit,
) {
    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
    var granted by remember {
        mutableStateOf(requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        })
    }
    var refused by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { allowed ->
        granted = requiredPermissions.all { permission -> allowed[permission] == true }
        refused = !granted
    }

    if (!granted) {
        CameraPermissionGate(
            activity = activity,
            refused = refused,
            onRequest = { request.launch(requiredPermissions) },
            onBack = onBack,
        )
        return
    }

    LivenessSurface(activity = activity, settings = settings)
}

/**
 * The rationale, in Spanish, before the system dialog rather than after it.
 *
 * Android only lets an app ask twice. Explaining first is the difference
 * between a user who taps "Permitir" and one who reflexively dismisses a
 * dialog that appeared out of nowhere and then cannot be asked again.
 */
@Composable
private fun CameraPermissionGate(
    activity: ComponentActivity,
    refused: Boolean,
    onRequest: () -> Unit,
    onBack: () -> Unit,
) {
    val permanentlyRefused = refused &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Necesitamos cámara y ubicación",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Antes de abrir la cámara, FaceCheck solicita cámara y ubicación " +
                "para proteger la operación. La ubicación exacta se envía cifrada junto " +
                "con la solicitud; el video nunca se graba ni se guarda en el teléfono.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (permanentlyRefused) {
            Text(
                text = "El permiso quedó bloqueado. Actívalo desde los ajustes del " +
                    "sistema para continuar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Abrir ajustes") }
        } else {
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir cámara y ubicación")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver a la configuración")
        }
    }
}

@Composable
private fun LivenessSurface(
    activity: ComponentActivity,
    settings: ImmersiveSettings,
) {
    val scope = rememberCoroutineScope()

    // Built through the expect/actual entry point rather than by calling the
    // Android class directly: this is the code path shared code takes, so it is
    // the one worth exercising on a real device.
    val camera = remember {
        runCatching {
            createCameraController(CameraHost(activity), CameraOptions()) as AndroidCameraController
        }.getOrNull()
    }
    val locationProvider = remember { AndroidLocationContextProvider(CameraHost(activity)) }
    DisposableEffect(camera) {
        onDispose { camera?.close() }
    }

    var activeSessionState by remember { mutableStateOf<StateFlow<ActiveLivenessState>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    val flowReducer = remember { ImmersiveFlowReducer() }
    var flowState by remember { mutableStateOf<ImmersiveFlowState>(flowReducer.state) }
    var stableSinceMs by remember { mutableStateOf<Long?>(null) }
    var stableForMs by remember { mutableStateOf(0L) }
    var framingHint by remember { mutableStateOf<PositioningHint?>(null) }
    var previewAspectRatio by remember { mutableStateOf(0f) }

    // Re-published into a plain StateFlow so the collection point does not have
    // to be conditional on `machine` being non-null — a composable called inside
    // a `?.` chain is a slot-table hazard nobody needs.
    val published = remember { MutableStateFlow<LivenessState>(LivenessState.Idle) }
    LaunchedEffect(activeSessionState) {
        val current = activeSessionState
        if (current == null) {
            published.value = LivenessState.Idle
        } else {
            current.collect { state ->
                if (state is ActiveLivenessState.Capturing) {
                    published.value = state.presentation
                }
            }
        }
    }
    val liveness by published.collectAsState()

    // The preview comes up as soon as the screen does, not when a button is
    // pressed: the SDK stops the camera at the end of every session, and a black
    // rectangle between attempts looks like a crash.
    LaunchedEffect(camera) { camera?.start() }

    LaunchedEffect(camera) {
        val current = camera ?: return@LaunchedEffect
        var shownAt = 0L
        var previousFrame: FaceFrame? = null
        current.frames
            // A camera that failed to open surfaces here; the session's own error
            // handling reports it properly, this collector just must not die
            // while updating the readiness gate.
            .catch { failure ->
                FaceCheckLogger.error {
                    "camera stream failed type=${failure::class.simpleName} " +
                        "code=${(failure as? FaceCheckException)?.code?.wire ?: "-"}"
                }
                framingHint = PositioningHint.NO_FACE
            }
            .collect { frame ->
                val now = SystemClock.elapsedRealtime()
                val previous = previousFrame
                if (now - shownAt >= DIAGNOSTIC_INTERVAL_MS) {
                    shownAt = now
                    previousFrame = frame
                }
                framingHint = framingHintFor(frame, previewAspectRatio)
                val readyCandidate = frame.isReadyCandidate(previewAspectRatio)
                if (readyCandidate && (previous == null || previous.isStableWith(frame))) {
                    stableSinceMs = stableSinceMs ?: now
                    stableForMs = now - stableSinceMs!!
                } else {
                    stableSinceMs = null
                    stableForMs = 0L
                }
                flowState = flowReducer.reduce(
                    ImmersiveFlowEvent.FaceFrameUpdated(
                        faceCount = frame.faceCount,
                        insideOval = readyCandidate,
                        stableForMs = stableForMs,
                    ),
                )
            }
    }

    fun startEnrollment() {
        val current = camera ?: return
        if (busy || flowState !is ImmersiveFlowState.Ready) return
        // Clear the previous attempt before changing the flow state so the old
        // error card cannot remain visible while the new session starts.
        outcome = null
        activeSessionState = null
        stableSinceMs = null
        stableForMs = 0L
        flowState = flowReducer.reduce(ImmersiveFlowEvent.StartPressed)
        scope.launch {
            busy = true
            outcome = try {
                val deviceLocation = locationProvider.currentLocation()
                val location = com.borealnetwork.facecheck.model.LocationContext(
                    latitude = deviceLocation.latitude,
                    longitude = deviceLocation.longitude,
                    accuracyMeters = deviceLocation.accuracyMeters,
                    capturedAt = deviceLocation.capturedAt,
                )
                val profile = FaceCheck.enrollmentModelProfiles().requireDefault()
                val prepared = FaceCheck.prepareEnrollment(
                    subjectId = settings.subjectId,
                    modelProfileId = profile.id,
                    location = location,
                )
                activeSessionState = prepared.state
                Outcome.Enrolled(
                    withContext(Dispatchers.Default) {
                        prepared.run(camera = current)
                    },
                )
            } catch (failure: FaceCheckException) {
                FaceCheckLogger.error {
                    "enrollment failed code=${failure.code.wire} " +
                        "http=${failure.httpStatus ?: "-"}"
                }
                Outcome.Rejected(failure)
            } catch (failure: Throwable) {
                FaceCheckLogger.error {
                    "enrollment crashed type=${failure::class.simpleName}"
                }
                Outcome.Crashed(failure)
            }
            busy = false
            flowState = when (val finished = outcome) {
                is Outcome.Enrolled ->
                    flowReducer.reduce(ImmersiveFlowEvent.Completed)

                is Outcome.Rejected ->
                    flowReducer.reduce(ImmersiveFlowEvent.ErrorRaised)

                is Outcome.Crashed ->
                    flowReducer.reduce(ImmersiveFlowEvent.ErrorRaised)

                else -> flowState
            }
            // The session released the camera on its way out; bring the preview
            // back so the screen is usable for the next attempt.
            current.start()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                previewAspectRatio = if (size.height == 0) 0f else size.width.toFloat() / size.height
            },
    ) {
        if (camera != null) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        // COMPATIBLE (TextureView) rather than PERFORMANCE
                        // (SurfaceView): a SurfaceView punches a hole through the
                        // window and the Compose overlay ends up behind it on some
                        // devices. The extra copy is invisible at preview
                        // resolution and the analysis path is untouched either way.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view -> camera.attachPreview(view) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        OvalGuide(state = liveness)
        // This must be above OvalGuide: its black scrim would otherwise dim
        // the white illuminator back to gray. Clearing the oval keeps the
        // camera preview and guide visible underneath it.
        FlashSurface(enabled = flashEnabled)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TopStrip(
                subjectId = settings.subjectId,
                environment = settings.environmentLabel,
                busy = busy,
                flashEnabled = flashEnabled,
                onFlashToggle = { flashEnabled = !flashEnabled },
            )

            Spacer(modifier = Modifier.weight(1f))

            InstructionPanel(
                state = liveness,
                stableForMs = stableForMs,
                framingHint = framingHint,
            )

            Spacer(modifier = Modifier.height(12.dp))

            outcome?.let { OutcomeCard(it) }

            Controls(
                enabled = camera != null && !busy && flowState is ImmersiveFlowState.Ready,
                busy = busy,
                onEnroll = ::startEnrollment,
            )
        }

        if (camera == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No se pudo iniciar la cámara en este dispositivo.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TopStrip(
    subjectId: String,
    environment: String,
    busy: Boolean,
    flashEnabled: Boolean,
    onFlashToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "FaceCheck",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subjectId,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                text = "Ambiente: $environment",
                style = MaterialTheme.typography.labelSmall,
                color = if (environment == "LIVE") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        }
        IconButton(onClick = onFlashToggle, enabled = !busy) {
            FlashGlyph(
                enabled = flashEnabled,
                description = if (flashEnabled) "Desactivar iluminador" else "Activar iluminador",
            )
        }
    }
}

@Composable
private fun FlashSurface(enabled: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .immersiveFlash(enabled),
    )
}

/**
 * The framing oval: a scrim with an oval punched out of it.
 *
 * `CompositingStrategy.Offscreen` is what makes [BlendMode.Clear] cut a hole in
 * the scrim instead of painting black onto the camera preview underneath.
 */
@Composable
private fun OvalGuide(state: LivenessState) {
    val colors = MaterialTheme.colorScheme
    val guide = when (state) {
        is LivenessState.Failed -> colors.error
        is LivenessState.Done, LivenessState.Capturing -> colors.primary
        is LivenessState.Positioning ->
            if (state.hint == PositioningHint.OK) colors.primary else Amber

        is LivenessState.ChallengeActive ->
            if (state.hint == null) colors.primary else Amber

        else -> Color.White.copy(alpha = 0.5f)
    }
    val strokeWidth = with(LocalDensity.current) { 4.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                val ovalWidth = size.width * OVAL_WIDTH_FRACTION
                val ovalHeight = ovalWidth * OVAL_ASPECT
                val topLeft = Offset(
                    x = (size.width - ovalWidth) / 2f,
                    y = size.height * OVAL_CENTRE_FRACTION - ovalHeight / 2f,
                )
                val ovalSize = Size(ovalWidth, ovalHeight)

                drawRect(color = Color.Black.copy(alpha = 0.55f))
                drawOval(
                    color = Color.Transparent,
                    topLeft = topLeft,
                    size = ovalSize,
                    blendMode = BlendMode.Clear,
                )
                drawOval(
                    color = guide,
                    topLeft = topLeft,
                    size = ovalSize,
                    style = Stroke(width = strokeWidth),
                )
            },
    )
}

@Composable
private fun InstructionPanel(
    state: LivenessState,
    stableForMs: Long,
    framingHint: PositioningHint?,
) {
    val countdown = stableCountdownSeconds(stableForMs)
    val displayState = if (state is LivenessState.Idle && framingHint != null) {
        LivenessState.Positioning(hint = framingHint)
    } else {
        state
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state is LivenessState.ChallengeActive) {
            Text(
                text = "Reto ${state.index + 1} de ${state.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (countdown != null) {
            Text(
                text = "Mantén la cara dentro del óvalo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = countdown.toString(),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // The whole point of the screen: the SDK's own Spanish instruction,
            // verbatim, big enough to read at arm's length while moving your head.
            Text(
                text = enrollmentInstruction(displayState),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LinearProgressIndicator(
            progress = {
                if (countdown != null) stableProgress(stableForMs) else displayState.progress.coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )

    }
}

@Composable
private fun Controls(
    enabled: Boolean,
    busy: Boolean,
    onEnroll: () -> Unit,
) {
    val controls = enrollmentControls(enabled = enabled, busy = busy)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!enabled && !busy) {
            Text(
                text = "Centra tu rostro dentro del ovalo y mantente quieto para comenzar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (controls.showPrimaryAction) {
            Button(
                onClick = onEnroll,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp),
            ) { Text(controls.primaryLabel, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: Outcome) {
    val colors = MaterialTheme.colorScheme
    val bad = outcome is Outcome.Rejected || outcome is Outcome.Crashed

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (bad) colors.errorContainer else colors.primaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val onColor = if (bad) colors.onErrorContainer else colors.onPrimaryContainer
            Text(
                text = title(outcome),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
            Text(
                text = detail(outcome),
                style = MaterialTheme.typography.bodySmall,
                color = onColor,
            )
        }
    }
}

private fun title(outcome: Outcome): String = when (outcome) {
    is Outcome.Enrolled -> "Enrolamiento completado"
    is Outcome.Rejected -> "No fue posible completar"
    is Outcome.Crashed -> "Ocurrió un error"
}

private fun detail(outcome: Outcome): String = when (outcome) {
    is Outcome.Enrolled -> "La persona quedó enrolada correctamente."
    is Outcome.Rejected -> outcome.error.message
    is Outcome.Crashed -> outcome.error.message ?: outcome.error.toString()
}

/** Fast enough to feel live, slow enough not to recompose on every frame. */
private const val DIAGNOSTIC_INTERVAL_MS = 120L

private fun FaceFrame.isReadyCandidate(previewAspectRatio: Float): Boolean =
    framingHintFor(this, previewAspectRatio) == null

private fun FaceFrame.isStableWith(previous: FaceFrame): Boolean =
    abs(yaw - previous.yaw) <= READY_MAX_MOVEMENT_DEG &&
        abs(pitch - previous.pitch) <= READY_MAX_MOVEMENT_DEG &&
        abs(roll - previous.roll) <= READY_MAX_MOVEMENT_DEG &&
        abs(faceRatio - previous.faceRatio) <= READY_MAX_FACE_RATIO_DELTA

private const val READY_MAX_MOVEMENT_DEG = 2.5f
private const val READY_MAX_FACE_RATIO_DELTA = 0.04f
