package com.borealnetwork.facecheck.sample

internal data class SubjectEnrollmentRecord(
    val subjectId: String,
    val enrolledAtMs: Long?,
)

internal object LocalSubjectEnrollmentMetadata {
    private const val SEPARATOR = "|"

    fun read(
        storedMetadata: String,
        legacySubjects: List<String>,
    ): List<SubjectEnrollmentRecord> {
        val records = storedMetadata
            .lineSequence()
            .mapNotNull(::parseLine)
            .toList()
        val bySubject = LinkedHashMap<String, SubjectEnrollmentRecord>()
        records.forEach { record ->
            bySubject.putIfAbsent(record.subjectId, record)
        }
        legacySubjects
            .mapNotNull { it.normalizedSubjectId() }
            .forEach { subjectId ->
                bySubject.putIfAbsent(subjectId, SubjectEnrollmentRecord(subjectId, null))
            }
        return bySubject.values.toList()
    }

    fun remember(
        existing: List<SubjectEnrollmentRecord>,
        subjectId: String,
        enrolledAtMs: Long,
    ): List<SubjectEnrollmentRecord> {
        val normalized = subjectId.normalizedSubjectId() ?: return existing.normalized()
        return listOf(SubjectEnrollmentRecord(normalized, enrolledAtMs)) +
            existing.normalized().filterNot { it.subjectId == normalized }
    }

    fun forget(
        existing: List<SubjectEnrollmentRecord>,
        subjectId: String,
    ): List<SubjectEnrollmentRecord> {
        val normalized = subjectId.normalizedSubjectId() ?: return existing.normalized()
        return existing.normalized().filterNot { it.subjectId == normalized }
    }

    fun serialize(records: List<SubjectEnrollmentRecord>): String =
        records.normalized()
            .map { record -> "${record.subjectId}$SEPARATOR${record.enrolledAtMs ?: ""}" }
            .joinToString("\n")

    private fun parseLine(line: String): SubjectEnrollmentRecord? {
        val parts = line.split(SEPARATOR, limit = 2)
        val subjectId = parts.getOrNull(0)?.normalizedSubjectId() ?: return null
        val enrolledAtMs = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        if (parts.size > 1 && parts[1].isNotBlank() && enrolledAtMs == null) return null
        return SubjectEnrollmentRecord(subjectId, enrolledAtMs)
    }

    private fun List<SubjectEnrollmentRecord>.normalized(): List<SubjectEnrollmentRecord> {
        val bySubject = LinkedHashMap<String, SubjectEnrollmentRecord>()
        forEach { record ->
            val subjectId = record.subjectId.normalizedSubjectId() ?: return@forEach
            bySubject.putIfAbsent(subjectId, record.copy(subjectId = subjectId))
        }
        return bySubject.values.toList()
    }

    private fun String.normalizedSubjectId(): String? = trim()
        .takeIf(sampleSubjectIdPattern::matches)
}
