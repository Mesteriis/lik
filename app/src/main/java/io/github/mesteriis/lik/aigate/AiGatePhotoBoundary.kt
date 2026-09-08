package io.github.mesteriis.lik.aigate

/** Task 13 replaces this conservative boundary after calibrated classification and BIOMETRIC_STRONG reveal exist. */
object AiGatePhotoBoundary {
    fun maySend(mediaId: String, revision: Long): Boolean {
        require(mediaId.isNotBlank() && revision >= 0)
        return false
    }
}
