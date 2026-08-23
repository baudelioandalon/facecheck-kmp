package com.borealnetwork.facecheck.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class IdentityDocumentContractTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun document_capture_policy_uses_stable_wire_values() {
        assertEquals(
            "\"FACE_ONLY\"",
            json.encodeToString(
                DocumentCapturePolicy.serializer(),
                DocumentCapturePolicy.FACE_ONLY,
            ),
        )
        assertEquals(
            "\"FACE_PLUS_INE\"",
            json.encodeToString(
                DocumentCapturePolicy.serializer(),
                DocumentCapturePolicy.FACE_PLUS_INE,
            ),
        )
    }

    @Test
    fun identity_document_round_trips_the_two_sides_independently() {
        val original = IdentityDocument(
            front = "front-bytes".encodeToByteArray(),
            back = "back-bytes".encodeToByteArray(),
        )

        val encoded = json.encodeToString(IdentityDocument.serializer(), original)
        val decoded = json.decodeFromString(IdentityDocument.serializer(), encoded)

        assertContentEquals(original.front, decoded.front)
        assertContentEquals(original.back, decoded.back)
    }

    @Test
    fun identity_document_status_keeps_the_expected_progress_states() {
        assertEquals("NONE", IdentityDocumentStatus.NONE.name)
        assertEquals("LEGACY_FRONT_ONLY", IdentityDocumentStatus.LEGACY_FRONT_ONLY.name)
        assertEquals("COMPLETE", IdentityDocumentStatus.COMPLETE.name)
        assertEquals("PROCESSING_FAILED", IdentityDocumentStatus.PROCESSING_FAILED.name)
    }
}
