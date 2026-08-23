package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.DocumentCapturePolicy
import com.borealnetwork.facecheck.model.ModelProfileSummary

internal val sampleSubjectIdPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{7,127}$")

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

internal object LocalSubjectDocuments {
    private const val SEPARATOR = "|"

    fun readAndMigrate(
        readStoredSubjects: () -> String,
        persistSubjects: (String) -> Unit,
    ): Map<String, Long?> {
        val storedSubjects = readStoredSubjects()
        val records = storedSubjects
            .lineSequence()
            .mapNotNull(::parseLine)
            .toList()
        val normalizedSubjects = normalizedDistinct(records).associate { it.subjectId to it.ineSavedAtMs }
        val persistedSubjects = serialize(normalizedSubjects)
        if (storedSubjects != persistedSubjects) persistSubjects(persistedSubjects)
        return normalizedSubjects
    }

    fun remember(existing: Map<String, Long?>, subjectId: String, ineSavedAtMs: Long): Map<String, Long?> {
        val normalized = subjectId.normalizedSubjectId() ?: return existing.normalized()
        return existing.normalized() + mapOf(normalized to ineSavedAtMs)
    }

    fun forget(existing: Map<String, Long?>, subjectId: String): Map<String, Long?> {
        val normalized = subjectId.normalizedSubjectId() ?: return existing.normalized()
        return existing.normalized().filterKeys { it != normalized }
    }

    fun serialize(records: Map<String, Long?>): String =
        records.normalized()
            .map { (subjectId, ineSavedAtMs) -> "${subjectId}$SEPARATOR${ineSavedAtMs ?: ""}" }
            .joinToString("\n")

    private fun parseLine(line: String): SubjectDocumentRecord? {
        val parts = line.split(SEPARATOR, limit = 2)
        val subjectId = parts.getOrNull(0)?.normalizedSubjectId() ?: return null
        val ineSavedAtMs = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        if (parts.size > 1 && parts[1].isNotBlank() && ineSavedAtMs == null) return null
        return SubjectDocumentRecord(subjectId, ineSavedAtMs)
    }

    private fun normalizedDistinct(values: List<SubjectDocumentRecord>): List<SubjectDocumentRecord> {
        val bySubject = LinkedHashMap<String, SubjectDocumentRecord>()
        values.forEach { record ->
            bySubject.putIfAbsent(record.subjectId, record)
        }
        return bySubject.values.toList()
    }

    private fun Map<String, Long?>.normalized(): Map<String, Long?> {
        val bySubject = LinkedHashMap<String, Long?>()
        forEach { (subjectId, ineSavedAtMs) ->
            val normalized = subjectId.normalizedSubjectId() ?: return@forEach
            bySubject.putIfAbsent(normalized, ineSavedAtMs)
        }
        return bySubject
    }

    private fun String.normalizedSubjectId(): String? = trim()
        .takeIf(sampleSubjectIdPattern::matches)
}

private data class SubjectDocumentRecord(
    val subjectId: String,
    val ineSavedAtMs: Long?,
)
