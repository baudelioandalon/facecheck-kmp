package com.borealnetwork.facecheck.model

import com.borealnetwork.facecheck.FaceCheckConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrandingContractTest {
    @Test
    fun local_override_recomputes_only_the_palette_and_preserves_canonical_identity() {
        val canonical = FaceCheckBranding(
            revision = 7,
            appName = "Empresa Demo",
            primaryColor = "#0066CC",
            palette = deriveBrandingPalette("#0066CC"),
            iconUrl = "https://storage.example.test/icon.webp",
            iconUrlExpiresAt = "2026-08-29T20:15:00.000Z",
            shortMessage = "Tu identidad segura",
        )

        val effective = canonical.withOverride(FaceCheckBrandingOverride("#006400"))

        assertEquals("Empresa Demo", effective.appName)
        assertEquals(7, effective.revision)
        assertEquals(canonical.iconUrl, effective.iconUrl)
        assertEquals(canonical.shortMessage, effective.shortMessage)
        assertEquals("#006400", effective.primaryColor)
        assertEquals("#1A741A", effective.palette.surface)
        assertEquals("#6AA56A", effective.palette.outline)
    }

    @Test
    fun config_rejects_non_hex_branding_without_touching_the_server() {
        assertFailsWith<FaceCheckException> {
            FaceCheckConfig(
                apiKey = "lk_test_a1b2c3d4e5f6g7h8",
                baseUrl = "https://facecheck.example.com",
                brandingOverride = FaceCheckBrandingOverride("blue"),
            )
        }
    }

    @Test
    fun public_branding_rejects_more_than_120_unicode_code_points() {
        assertFailsWith<IllegalArgumentException> {
            FaceCheckBranding(
                revision = 1,
                appName = "a".repeat(121),
                primaryColor = "#0066CC",
                palette = deriveBrandingPalette("#0066CC"),
            )
        }

        FaceCheckBranding(
            revision = 1,
            appName = "😀".repeat(120),
            primaryColor = "#0066CC",
            palette = deriveBrandingPalette("#0066CC"),
        )
    }

    @Test
    fun public_branding_requires_icon_url_and_expiration_together() {
        assertFailsWith<IllegalArgumentException> {
            FaceCheckBranding(
                revision = 1,
                appName = "Empresa Demo",
                primaryColor = "#0066CC",
                palette = deriveBrandingPalette("#0066CC"),
                iconUrl = "https://storage.example.test/icon.webp",
            )
        }
    }
}
