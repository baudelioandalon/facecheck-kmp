package com.borealnetwork.facecheck.immersive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun flashOverlayAlpha(enabled: Boolean): Float = if (enabled) 1f else 0f

/** A screen illuminator: it leaves the face oval clear and brightens its surroundings. */
fun Modifier.immersiveFlash(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                val ovalWidth = size.width * OVAL_WIDTH_FRACTION
                val ovalHeight = ovalWidth * OVAL_ASPECT
                val topLeft = Offset(
                    x = (size.width - ovalWidth) / 2f,
                    y = size.height * OVAL_CENTRE_FRACTION - ovalHeight / 2f,
                )

                drawRect(Color.White.copy(alpha = flashOverlayAlpha(enabled)))
                drawOval(
                    color = Color.Transparent,
                    topLeft = topLeft,
                    size = Size(ovalWidth, ovalHeight),
                    blendMode = BlendMode.Clear,
                )
            }
    }

@Composable
internal fun FlashGlyph(enabled: Boolean, description: String) {
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = description },
    ) {
        val bolt = Path().apply {
            moveTo(size.width * 0.58f, 0f)
            lineTo(size.width * 0.16f, size.height * 0.56f)
            lineTo(size.width * 0.48f, size.height * 0.56f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width * 0.84f, size.height * 0.34f)
            lineTo(size.width * 0.53f, size.height * 0.34f)
            close()
        }
        drawPath(
            path = bolt,
            color = if (enabled) Color(0xFFFFD54F) else Color.White,
        )
    }
}

internal const val OVAL_WIDTH_FRACTION = 0.72f
internal const val OVAL_ASPECT = 1.32f
internal const val OVAL_CENTRE_FRACTION = 0.36f
