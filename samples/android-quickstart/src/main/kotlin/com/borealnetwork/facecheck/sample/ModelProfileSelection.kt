package com.borealnetwork.facecheck.sample

import com.borealnetwork.facecheck.model.ModelProfileCatalog
import com.borealnetwork.facecheck.model.ModelProfileSummary
import java.util.Locale

internal object ModelProfileSelection {
    fun selectDefault(catalog: ModelProfileCatalog): ModelProfileSummary? =
        catalog.defaultProfile ?: catalog.profiles.minByOrNull { it.rank }

    fun label(profile: ModelProfileSummary): String {
        val firstLine = buildString {
            append(profile.rank)
            append(" · ")
            append(profile.displayName)
            profile.badge?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        val pad = profile.passivePadArtifactBytes
        val secondLine = if (pad == null) {
            "Reconocimiento ${mb(profile.recognizerArtifactBytes)} MB · " +
                "Total backend ${mb(profile.totalArtifactBytes)} MB"
        } else {
            "Reconocimiento ${mb(profile.recognizerArtifactBytes)} MB · " +
                "Anti-spoof ${mb(pad)} MB · " +
                "Total backend ${mb(profile.totalArtifactBytes)} MB"
        }
        return "$firstLine\n$secondLine"
    }

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes.toDouble() / 1_000_000.0)
}
