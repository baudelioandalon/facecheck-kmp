package com.borealnetwork.facecheck.immersive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import com.borealnetwork.facecheck.SubjectId
import kotlinx.coroutines.launch

@Composable
fun ImmersiveSampleRoot(
    activity: ComponentActivity,
    initial: ImmersiveSettings,
    onSettingsSaved: suspend (ImmersiveSettings) -> Unit,
    onBack: () -> Unit = {},
    initialError: String? = null,
) {
    var settings by remember(initial) { mutableStateOf(initial) }

    ImmersiveTheme {
        if (settings.isComplete) {
            ImmersiveCameraScreen(
                activity = activity,
                settings = settings,
                onBack = {
                    settings = ImmersiveSettings()
                    onBack()
                },
            )
        } else {
            ImmersiveSettingsScreen(
                initial = settings,
                initialError = initialError,
                onReady = { ready ->
                    onSettingsSaved(ready)
                    settings = ready
                },
            )
        }
    }
}

@Composable
private fun ImmersiveSettingsScreen(
    initial: ImmersiveSettings,
    initialError: String?,
    onReady: suspend (ImmersiveSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var subjectId by remember(initial) { mutableStateOf(initial.subjectId) }
    var error by remember(initial, initialError) { mutableStateOf(initialError) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "FaceCheck",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Configura el servicio antes de enrolar a una persona.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; error = null },
            label = { Text("URL del servicio") },
            placeholder = { Text("https://us-central1-facecheck-mx.cloudfunctions.net") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; error = null },
            label = { Text("Llave de API") },
            placeholder = { Text("lk_test_…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = subjectId,
            onValueChange = { subjectId = it; error = null },
            label = { Text("ID de la persona") },
            placeholder = { Text("persona_01") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        OutlinedButton(
            onClick = {
                runCatching { SubjectId.generate(apiKey) }
                    .onSuccess {
                        subjectId = it
                        error = null
                    }
                    .onFailure { error = it.message ?: "No fue posible generar el ID." }
            },
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Generar ID aleatorio") }
        Button(
            onClick = {
                val candidate = ImmersiveSettings(baseUrl, apiKey, subjectId)
                if (!candidate.isComplete) {
                    error = "Completa los tres campos."
                } else if (!Regex("^[A-Za-z][A-Za-z0-9_-]{7,127}$").matches(candidate.subjectId)) {
                    error = "El ID debe empezar con una letra y tener entre 8 y 128 caracteres."
                } else {
                    scope.launch {
                        runCatching { onReady(candidate) }
                            .onFailure { error = it.message ?: "No fue posible guardar la configuración." }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuar a la cámara") }
        Text(
            text = "La configuración se conserva en el dispositivo. La llave de API " +
                "no se escribe en el código fuente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
