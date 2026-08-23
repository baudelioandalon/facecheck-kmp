package com.borealnetwork.facecheck.net

import com.borealnetwork.facecheck.liveness.CapturedEvidenceBundle
import com.borealnetwork.facecheck.liveness.LivenessSessionDescriptor
import com.borealnetwork.facecheck.model.CompareWith
import com.borealnetwork.facecheck.model.DocumentCapturePolicy
import com.borealnetwork.facecheck.model.EnrollResult
import com.borealnetwork.facecheck.model.IdentityDocument
import com.borealnetwork.facecheck.model.IneFrontValidationResult
import com.borealnetwork.facecheck.model.LocationContext
import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.VerifyResult

internal interface FaceCheckBackend {
    suspend fun enroll(
        subjectId: String,
        selfie: ByteArray,
        ine: ByteArray? = null,
        grant: String? = null,
        overwrite: Boolean = false,
    ): EnrollResult

    suspend fun attachIdentityDocument(
        subjectId: String,
        document: IdentityDocument,
        grant: String? = null,
    ): EnrollResult

    suspend fun validateIneFront(
        subjectId: String,
        front: ByteArray,
    ): IneFrontValidationResult

    suspend fun verify(
        subjectId: String,
        selfie: ByteArray,
        compareWith: CompareWith = CompareWith.ENROLLMENT,
    ): VerifyResult

    suspend fun getEnrollmentModelProfiles(): ModelProfileCatalog

    suspend fun createLivenessSession(
        operation: String,
        subjectId: String,
        requestedModelProfileId: String? = null,
        requestedDocumentPolicy: DocumentCapturePolicy = DocumentCapturePolicy.FACE_ONLY,
        location: LocationContext,
    ): LivenessSessionDescriptor

    suspend fun enroll(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        grant: String? = null,
        overwrite: Boolean = false,
        ine: ByteArray? = null,
    ): EnrollResult

    suspend fun verify(
        session: LivenessSessionDescriptor,
        evidence: CapturedEvidenceBundle,
        compareWith: CompareWith = CompareWith.ENROLLMENT,
    ): VerifyResult

    fun close()
}
