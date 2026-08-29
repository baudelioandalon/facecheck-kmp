package com.borealnetwork.facecheck.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val BRANDING_INPUT_COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
private val BRANDING_COLOR_PATTERN = Regex("^#[0-9A-F]{6}$")
private val BRANDING_TIMESTAMP_PATTERN = Regex(
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z$",
)

/** A local, in-memory color override. It never mutates tenant branding in Firestore. */
class FaceCheckBrandingOverride(primaryColor: String) {
    val primaryColor: String = normalizeBrandingColor(primaryColor)

    override fun equals(other: Any?): Boolean =
        other is FaceCheckBrandingOverride && other.primaryColor == primaryColor

    override fun hashCode(): Int = primaryColor.hashCode()

    override fun toString(): String = "FaceCheckBrandingOverride(primaryColor=$primaryColor)"
}

@Serializable
data class FaceCheckBrandingPalette(
    val primary: String,
    val onPrimary: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val outline: String,
) {
    init {
        listOf(primary, background, surface, outline).forEach(::requireBrandingColor)
        listOf(onPrimary, onBackground, onSurface).forEach { color ->
            require(color == "#FFFFFF" || color == "#08111F") { "Invalid branding text color." }
        }
    }
}

/** Public, read-only identity supplied by `GET /branding`. */
@Serializable
data class FaceCheckBranding(
    val version: String = "1.0",
    val revision: Int,
    val appName: String,
    val primaryColor: String,
    val palette: FaceCheckBrandingPalette,
    val iconUrl: String? = null,
    val iconUrlExpiresAt: String? = null,
    val shortMessage: String? = null,
) {
    init {
        require(version == "1.0") { "Unsupported branding version." }
        require(revision >= 0) { "Invalid branding revision." }
        require(appName.isNotBlank() && appName.brandingCodePointCount() <= 120) {
            "Invalid branding app name."
        }
        require(appName.hasNoBrandingControlCharacters()) { "Invalid branding app name." }
        requireBrandingColor(primaryColor)
        require(palette.primary == primaryColor && palette.background == primaryColor) {
            "Invalid branding palette."
        }
        require((iconUrl == null) == (iconUrlExpiresAt == null)) {
            "Branding icon URL and expiration must be supplied together."
        }
        if (iconUrl != null) {
            require(iconUrl.length in 9..4096 && iconUrl.startsWith("https://")) {
                "Invalid branding icon URL."
            }
            require(BRANDING_TIMESTAMP_PATTERN.matches(checkNotNull(iconUrlExpiresAt))) {
                "Invalid branding icon expiration."
            }
        }
        if (shortMessage != null) {
            require(
                shortMessage.isNotEmpty() &&
                    shortMessage.brandingCodePointCount() <= 120 &&
                    shortMessage.hasNoBrandingControlCharacters(),
            ) { "Invalid branding short message." }
        }
    }

    fun withOverride(override: FaceCheckBrandingOverride?): FaceCheckBranding {
        if (override == null || override.primaryColor == primaryColor) return this
        return copy(
            primaryColor = override.primaryColor,
            palette = deriveBrandingPalette(override.primaryColor),
        )
    }
}

internal fun deriveBrandingPalette(primaryColor: String): FaceCheckBrandingPalette {
    val primary = normalizeBrandingColor(primaryColor)
    val onPrimary = betterBrandingText(primary)
    val surface = if (onPrimary == "#FFFFFF") {
        mixBrandingColor(primary, "#FFFFFF", 0.10)
    } else {
        mixBrandingColor(primary, "#000000", 0.08)
    }
    val onSurface = betterBrandingText(surface)
    return FaceCheckBrandingPalette(
        primary = primary,
        onPrimary = onPrimary,
        background = primary,
        onBackground = onPrimary,
        surface = surface,
        onSurface = onSurface,
        outline = mixBrandingColor(onSurface, surface, 0.65),
    )
}

private fun normalizeBrandingColor(value: String): String {
    if (!BRANDING_INPUT_COLOR_PATTERN.matches(value)) {
        throw FaceCheckException(
            code = FaceCheckErrorCode.INVALID_CONFIG,
            message = "primaryColor debe usar el formato hexadecimal #RRGGBB.",
        )
    }
    return value.uppercase()
}

private fun requireBrandingColor(value: String) {
    require(BRANDING_COLOR_PATTERN.matches(value)) { "Invalid branding color." }
}

private fun String.hasNoBrandingControlCharacters(): Boolean = none { character ->
    character.code in 0x00..0x1F || character.code in 0x7F..0x9F
}

private fun String.brandingCodePointCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val first = this[index].code
        val hasLowSurrogate = first in 0xD800..0xDBFF &&
            index + 1 < length && this[index + 1].code in 0xDC00..0xDFFF
        index += if (hasLowSurrogate) 2 else 1
        count++
    }
    return count
}

private fun betterBrandingText(background: String): String =
    if (contrastRatio(background, "#FFFFFF") >= contrastRatio(background, "#08111F")) {
        "#FFFFFF"
    } else {
        "#08111F"
    }

private fun contrastRatio(left: String, right: String): Double {
    val leftLuminance = luminance(left)
    val rightLuminance = luminance(right)
    val lighter = max(leftLuminance, rightLuminance)
    val darker = min(leftLuminance, rightLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun luminance(color: String): Double {
    val channels = parseColor(color)
    return (0.2126 * linearChannel(channels[0])) +
        (0.7152 * linearChannel(channels[1])) +
        (0.0722 * linearChannel(channels[2]))
}

private fun linearChannel(channel: Int): Double {
    val srgb = channel / 255.0
    return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
}

private fun mixBrandingColor(color: String, target: String, weight: Double): String {
    val source = parseColor(color)
    val destination = parseColor(target)
    return toColorHex(
        IntArray(3) { index ->
            (source[index] * (1 - weight) + destination[index] * weight).roundToInt()
        },
    )
}

private fun parseColor(color: String): IntArray = intArrayOf(
    color.substring(1, 3).toInt(16),
    color.substring(3, 5).toInt(16),
    color.substring(5, 7).toInt(16),
)

private fun toColorHex(channels: IntArray): String = "#" + channels.joinToString("") { channel ->
    channel.coerceIn(0, 255).toString(16).padStart(2, '0')
}.uppercase()
