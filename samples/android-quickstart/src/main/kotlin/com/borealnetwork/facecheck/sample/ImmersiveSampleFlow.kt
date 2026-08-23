package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.DocumentCapturePolicy
import com.borealnetwork.facecheck.model.ModelProfileSummary

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
        val documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
    ) : ImmersiveScreen

    data object VerificationDirectory : ImmersiveScreen

    /** Camera-only alignment before the user explicitly starts a backend session. */
    data class CameraPreflight(
        val operation: SampleOperation,
        val subjectId: String,
        val documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        val enrollmentProfile: ModelProfileSummary? = null,
    ) : ImmersiveScreen

    data class Capture(
        val operation: SampleOperation,
        val subjectId: String,
        val documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        val enrollmentProfile: ModelProfileSummary? = null,
    ) : ImmersiveScreen

    data class DocumentCapture(
        val subjectId: String,
        val front: ByteArray? = null,
        val back: ByteArray? = null,
        val message: String? = null,
        val isUploading: Boolean = false,
    ) : ImmersiveScreen

    data class Outcome(
        val operation: SampleOperation,
        val succeeded: Boolean,
        val message: String,
    ) : ImmersiveScreen
}

internal object ImmersiveSampleFlow {
    fun beginAfterPreconditions(
        operation: SampleOperation,
        subjectId: String,
        blockingMessage: String?,
        documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        enrollmentProfile: ModelProfileSummary? = null,
    ): ImmersiveScreen =
        blockingMessage?.let(ImmersiveScreen::PermissionGate)
            ?: begin(operation, subjectId, documentPolicy, enrollmentProfile)

    fun begin(
        operation: SampleOperation,
        subjectId: String,
        documentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        enrollmentProfile: ModelProfileSummary? = null,
    ): ImmersiveScreen {
        val normalized = subjectId.trim()
        return if (sampleSubjectIdPattern.matches(normalized)) {
            ImmersiveScreen.CameraPreflight(operation, normalized, documentPolicy, enrollmentProfile)
        } else {
            ImmersiveScreen.SubjectSetup(
                operation = operation,
                validationMessage = "Escribe un ID de persona válido.",
                subjectId = normalized,
                documentPolicy = documentPolicy,
            )
        }
    }
}

internal object LocalSubjectDirectory {
    fun readAndMigrate(
        readStoredSubjects: () -> String,
        persistSubjects: (String) -> Unit,
    ): List<String> {
        val storedSubjects = readStoredSubjects()
        val normalizedSubjects = normalizedDistinct(storedSubjects.lineSequence().toList())
        val persistedSubjects = normalizedSubjects.joinToString("\n")
        if (storedSubjects != persistedSubjects) persistSubjects(persistedSubjects)
        return normalizedSubjects
    }

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
