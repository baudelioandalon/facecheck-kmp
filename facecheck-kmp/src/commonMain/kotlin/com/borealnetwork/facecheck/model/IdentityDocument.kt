package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * The two sides of the identity document that the backend receives.
 *
 * The front is the one that carries the face used for comparison plus the
 * visible textual fields extracted by OCR. The back is retained as evidence and
 * for future document-validation work.
 */
@Serializable
data class IdentityDocument(
    val front: ByteArray,
    val back: ByteArray,
)
