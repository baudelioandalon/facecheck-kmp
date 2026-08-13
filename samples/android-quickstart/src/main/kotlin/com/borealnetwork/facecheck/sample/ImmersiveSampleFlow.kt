package com.borealnetwork.facecheck.sample

private val sampleSubjectIdPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{7,127}$")

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
        val subjectId: String = "",
    ) : ImmersiveScreen

    data object VerificationDirectory : ImmersiveScreen

    data class Capture(
        val operation: SampleOperation,
        val subjectId: String,
    ) : ImmersiveScreen

    data class Outcome(
        val operation: SampleOperation,
        val succeeded: Boolean,
        val message: String,
    ) : ImmersiveScreen
}

internal object ImmersiveSampleFlow {
    fun begin(operation: SampleOperation, subjectId: String): ImmersiveScreen {
        val normalized = subjectId.trim()
        return if (sampleSubjectIdPattern.matches(normalized)) {
            ImmersiveScreen.Capture(operation, normalized)
        } else {
            ImmersiveScreen.SubjectSetup(operation, "Escribe un ID de persona válido.", normalized)
        }
    }
}

internal object LocalSubjectDirectory {
    fun remember(existing: List<String>, successfulEnrollment: String): List<String> {
        val normalized = successfulEnrollment.normalizedSubjectId() ?: return normalizedDistinct(existing)
        return normalizedDistinct(listOf(normalized) + existing)
    }

    fun normalizedDistinct(values: List<String>): List<String> = values
        .mapNotNull { it.normalizedSubjectId() }
        .distinct()

    private fun String.normalizedSubjectId(): String? = trim()
        .takeIf(sampleSubjectIdPattern::matches)
}
