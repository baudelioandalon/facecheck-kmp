package com.borealnetwork.facecheck.sample

internal enum class FaceGuideLighting(
    val buttonLabel: String,
    val contentDescription: String,
    val illuminatesOutsideGuide: Boolean,
    val requiresDarkButtonText: Boolean,
) {
    Normal(
        buttonLabel = "⚡",
        contentDescription = "Activar flash visual para poca luz",
        illuminatesOutsideGuide = false,
        requiresDarkButtonText = false,
    ),
    LowLight(
        buttonLabel = "⚡",
        contentDescription = "Desactivar flash visual para poca luz",
        illuminatesOutsideGuide = true,
        requiresDarkButtonText = true,
    );

    fun toggle(): FaceGuideLighting = when (this) {
        Normal -> LowLight
        LowLight -> Normal
    }
}
