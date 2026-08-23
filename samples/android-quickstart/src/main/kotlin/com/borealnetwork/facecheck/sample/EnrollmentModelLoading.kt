package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.ModelProfileSummary

internal object EnrollmentModelLoading {

    data class State(
        val selectedProfile: ModelProfileSummary?,
        val profiles: List<ModelProfileSummary>,
        val message: String,
        val canContinue: Boolean,
    )

    suspend fun load(fetchCatalog: suspend () -> ModelProfileCatalog): State {
        return runCatching { fetchCatalog() }
            .fold(
                onSuccess = { catalog ->
                    val profile = ModelProfileSelection.selectDefault(catalog)
                    val profiles = catalog.profiles.sortedWith(
                        compareBy<ModelProfileSummary> { it.rank }.thenBy { it.id },
                    )
                    if (profile == null) {
                        State(
                            selectedProfile = null,
                            profiles = emptyList(),
                            message = EMPTY_CATALOG_MESSAGE,
                            canContinue = false,
                        )
                    } else {
                        State(
                            selectedProfile = profile,
                            profiles = profiles,
                            message = ModelProfileSelection.label(profile),
                            canContinue = true,
                        )
                    }
                },
                onFailure = { error ->
                    State(
                        selectedProfile = null,
                        profiles = emptyList(),
                        message = "${ERROR_PREFIX}${error.message}",
                        canContinue = false,
                    )
                },
            )
    }

    private const val EMPTY_CATALOG_MESSAGE =
        "No hay modelos disponibles para este ambiente. En producción solo se muestran modelos comercialmente autorizados."
    private const val ERROR_PREFIX = "No pudimos cargar los modelos: "
}
