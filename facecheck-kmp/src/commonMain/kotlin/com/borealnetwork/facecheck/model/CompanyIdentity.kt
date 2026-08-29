package com.borealnetwork.facecheck.model

/** Validates the read-only company identity attached by the FaceCheck backend. */
internal object CompanyIdentity {
    private val PATTERN = Regex("^cmp_[A-Za-z0-9_-]{22}$")

    fun requireValid(companyId: String?) {
        require(companyId == null || PATTERN.matches(companyId)) {
            "Company identity returned by the server is invalid."
        }
    }
}
