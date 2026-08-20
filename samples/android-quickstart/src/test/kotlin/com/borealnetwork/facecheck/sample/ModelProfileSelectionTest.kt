package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.ModelProfileSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelProfileSelectionTest {

    @Test
    fun `selector formats recognizer pad and total as decimal backend mb`() {
        val text = ModelProfileSelection.label(
            profile(
                rank = 1,
                recognizerBytes = 13_616_099,
                padBytes = 3_705_583,
                totalBytes = 17_321_682,
            ),
        )

        assertEquals(
            "1 · ArcFace Mobile · Recomendado\n" +
                "Reconocimiento 13.6 MB · Anti-spoof 3.7 MB · Total backend 17.3 MB",
            text,
        )
    }

    @Test
    fun `selector uses backend default not first array item`() {
        val catalog = ModelProfileCatalog(
            defaultProfileId = "profile-b",
            profiles = listOf(
                profile(id = "profile-a", rank = 1),
                profile(id = "profile-b", rank = 2),
            ),
        )

        assertEquals("profile-b", ModelProfileSelection.selectDefault(catalog)?.id)
    }

    private fun profile(
        id: String = "profile-a",
        rank: Int = 1,
        recognizerBytes: Long = 13_616_099,
        padBytes: Long? = null,
        totalBytes: Long = recognizerBytes + (padBytes ?: 0L),
    ) = ModelProfileSummary(
        id = id,
        rank = rank,
        displayName = "ArcFace Mobile",
        availability = "test",
        badge = "Recomendado",
        recognizerArtifactBytes = recognizerBytes,
        passivePadArtifactBytes = padBytes,
        totalArtifactBytes = totalBytes,
    )
}
