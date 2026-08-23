package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.ModelProfileCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnrollmentModelLoadingTest {

    @Test
    fun `loaders selects the default profile and enables continue`() = runBlocking {
        val state = EnrollmentModelLoading.load {
            catalog(
                defaultProfileId = "profile-b",
                profiles = listOf(
                    profile(id = "profile-a", rank = 3),
                    profile(id = "profile-b", rank = 1, badge = "Recomendado"),
                ),
            )
        }

        assertTrue(state.canContinue)
        assertEquals("profile-b", state.selectedProfile?.id)
        assertEquals(listOf("profile-b", "profile-a"), state.profiles.map { it.id })
        assertEquals(
            "1 · ArcFace Mobile · Recomendado\n" +
                "Reconocimiento 13.6 MB · Total backend 13.6 MB",
            state.message,
        )
    }

    @Test
    fun `loader disables continue when the catalog is empty`() = runBlocking {
        val state = EnrollmentModelLoading.load {
            catalog(profiles = emptyList())
        }

        assertFalse(state.canContinue)
        assertEquals(emptyList(), state.profiles)
        assertEquals(
            "No hay modelos disponibles para este ambiente. En producción solo se muestran modelos comercialmente autorizados.",
            state.message,
        )
    }

    @Test
    fun `loader disables continue when the catalog request fails`() = runBlocking {
        val state = EnrollmentModelLoading.load {
            error("boom")
        }

        assertFalse(state.canContinue)
        assertEquals(emptyList(), state.profiles)
        assertEquals("No pudimos cargar los modelos: boom", state.message)
    }

    private fun catalog(
        defaultProfileId: String = "profile-a",
        profiles: List<com.borealnetwork.facecheck.model.ModelProfileSummary> = listOf(
            profile(),
        ),
    ): ModelProfileCatalog = ModelProfileCatalog(
        defaultProfileId = defaultProfileId,
        profiles = profiles,
    )

    private fun profile(
        id: String = "profile-a",
        rank: Int = 1,
        badge: String? = null,
    ) = com.borealnetwork.facecheck.model.ModelProfileSummary(
        id = id,
        rank = rank,
        displayName = "ArcFace Mobile",
        availability = "test",
        badge = badge,
        recognizerArtifactBytes = 13_616_099,
        passivePadArtifactBytes = null,
        totalArtifactBytes = 13_616_099,
    )
}
