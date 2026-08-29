package com.borealnetwork.facecheck

/**
 * Creates opaque IDs for biometric subjects.
 *
 * A generated value combines a stable, one-way API-key fingerprint with fresh
 * randomness. It is not an API key and must still be stored by the integrator
 * with the account it represents; generating a new value for every verification
 * would address a different subject.
 */
expect object SubjectId {
    fun generate(apiKey: String): String
}
