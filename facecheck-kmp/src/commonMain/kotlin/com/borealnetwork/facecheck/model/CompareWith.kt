package com.borealnetwork.facecheck.model

/**
 * Which stored template a verification is matched against.
 *
 * This is a request, not a guarantee. The backend raises it to the strictest
 * comparison the tenant's config allows and never lowers it: a tenant with
 * `requireIneMatch` on resolves every request to [BOTH], and a bare [INE]
 * request is widened to [BOTH] as well. See `effective_compare_with` in
 * `functions-python/facecheck/decision.py` — the field is attacker-chosen
 * whenever the API key is, so it may only ever add a check.
 */
enum class CompareWith(val wire: String) {
    /** Selfie against the enrolled reference photo. The default. */
    ENROLLMENT("enrollment"),

    /**
     * Selfie against the portrait on the enrolled ID document.
     *
     * Always widened to [BOTH] by the backend; asking for it alone would decide
     * the verification on the looser ID threshold by itself.
     */
    INE("ine"),

    /** Both the reference photo and the ID portrait must match. */
    BOTH("both"),
    ;

    companion object {
        fun fromWire(wire: String?): CompareWith =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: ENROLLMENT
    }
}