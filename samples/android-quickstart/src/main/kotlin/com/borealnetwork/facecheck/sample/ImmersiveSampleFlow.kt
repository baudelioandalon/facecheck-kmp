package com.borealnetwork.facecheck.sample

internal enum class SampleOperation {
    ENROLL,
    VERIFY,
}

internal sealed interface ImmersiveScreen {
    data object Home : ImmersiveScreen

    data class PermissionGate(val message: String) : ImmersiveScreen

    data class SubjectSetup(
        val operation: SampleOperation,
        val validationMessage: String? = null,
    ) : ImmersiveScreen

    data class Capture(
        val operation: SampleOperation,
        val email: String,
    ) : ImmersiveScreen

    data class Outcome(
        val operation: SampleOperation,
        val succeeded: Boolean,
        val message: String,
    ) : ImmersiveScreen
}

internal object ImmersiveSampleFlow {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun begin(operation: SampleOperation, email: String): ImmersiveScreen {
        val normalized = email.trim()
        return if (emailPattern.matches(normalized)) {
            ImmersiveScreen.Capture(operation, normalized)
        } else {
            ImmersiveScreen.SubjectSetup(operation, "Escribe un correo válido.")
        }
    }
}

internal object LocalSubjectDirectory {
    fun remember(existing: List<String>, successfulEnrollment: String): List<String> {
        val normalized = successfulEnrollment.normalizedEmail() ?: return normalizedDistinct(existing)
        return normalizedDistinct(listOf(normalized) + existing)
    }

    fun normalizedDistinct(values: List<String>): List<String> = values
        .mapNotNull { it.normalizedEmail() }
        .distinct()

    private fun String.normalizedEmail(): String? = trim()
        .lowercase()
        .takeIf { it.isNotBlank() }
}
