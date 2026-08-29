package com.borealnetwork.facecheck.immersive

import com.borealnetwork.facecheck.liveness.LivenessState

internal data class EnrollmentControls(
    val primaryLabel: String,
    val showPrimaryAction: Boolean,
    val showVerificationAction: Boolean,
    val showOverwriteToggle: Boolean,
    val showTestCapture: Boolean,
    val showSettingsAction: Boolean,
)

internal fun enrollmentControls(
    enabled: Boolean,
    busy: Boolean,
): EnrollmentControls = EnrollmentControls(
    primaryLabel = if (busy) "…" else "Empezar",
    showPrimaryAction = true,
    showVerificationAction = false,
    showOverwriteToggle = false,
    showTestCapture = false,
    showSettingsAction = false,
)

internal fun enrollmentInstruction(state: LivenessState): String =
    state.instructionEs
        .replace("para la verificación", "para el enrolamiento")
        .replace("verificación", "enrolamiento")
        .replace("Verificación", "Enrolamiento")
