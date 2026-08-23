package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable

/**
 * How much document capture the host app requests for the current operation.
 *
 * `FACE_ONLY` keeps the flow lightweight. `FACE_PLUS_INE` allows the host to
 * ask for INE capture either during enrolment or later in an independent
 * document-attachment flow.
 */
@Serializable
enum class DocumentCapturePolicy {
    FACE_ONLY,
    FACE_PLUS_INE,
}
