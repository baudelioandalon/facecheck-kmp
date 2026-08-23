package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * Result of validating the INE front portrait against an already enrolled face.
 *
 * This call is intentionally separate from attaching the full document: it lets
 * an app show immediate feedback before asking the user to capture the reverse
 * side, while the final attach call still revalidates server-side before saving.
 */
@Serializable
data class IneFrontValidationResult(
    val subjectId: String,
    val matched: Boolean,
    val matchScore: Double,
    val similarityPercent: Double,
    val validatedBy: String,
    val validatedAt: String,
    val modelProfileId: String,
    val embeddingSchemaId: String,
)
