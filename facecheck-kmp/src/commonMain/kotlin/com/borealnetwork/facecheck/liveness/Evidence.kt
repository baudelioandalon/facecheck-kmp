package com.borealnetwork.facecheck.liveness

/**
 * Canonical evidence roles the backend expects for Active Liveness v1.
 *
 * The server owns the turn order. These five role names are the stable wire
 * contract; the UI may collapse `center_between` into the previous/next visual
 * step, but the multipart body must still include it.
 */
enum class EvidenceRole(val wire: String) {
    FRONT_INITIAL("front_initial"),
    TURN_FIRST("turn_first"),
    CENTER_BETWEEN("center_between"),
    TURN_SECOND("turn_second"),
    FRONT_FINAL("front_final"),
}

data class CapturedJpeg(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(bytes.isNotEmpty()) { "captured JPEG must not be empty" }
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedJpeg) return false
        return width == other.width &&
            height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

data class CapturedEvidence(
    val role: EvidenceRole,
    val jpeg: CapturedJpeg,
    val sha256: String? = null,
) {
    constructor(role: EvidenceRole, bytes: ByteArray) : this(
        role = role,
        jpeg = CapturedJpeg(bytes = bytes, width = 1, height = 1),
        sha256 = null,
    )
}

data class CapturedEvidenceBundle(
    val images: List<CapturedEvidence>,
) {
    init {
        require(images.map { it.role } == EvidenceRole.entries) {
            "evidence must be in canonical role order"
        }
    }
}
