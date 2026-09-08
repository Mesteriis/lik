package io.github.mesteriis.lik.catalog

import java.security.MessageDigest

enum class MediaSource { DEVICE, GOOGLE_IMPORT }

/** Identity describes an object, never its name, date, or current content revision. */
@ConsistentCopyVisibility
data class MediaIdentity private constructor(
    val mediaId: String,
    val source: MediaSource,
    val sourceKey: String,
    val volumeName: String = "",
    val volumeVersion: String = "",
    val generationAdded: Long = 0,
) {
    companion object {
        fun imported(sha256: String): MediaIdentity {
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid private file identity" }
            return MediaIdentity(sha256, MediaSource.GOOGLE_IMPORT, sha256)
        }

        fun device(volumeName: String, volumeVersion: String, rowId: Long, generationAdded: Long): MediaIdentity {
            require(volumeName.isNotBlank() && volumeVersion.isNotBlank())
            require(rowId >= 0 && generationAdded >= 0)
            val key = listOf(volumeName, volumeVersion, rowId.toString(), generationAdded.toString())
                .joinToString("") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return MediaIdentity("device:$digest", MediaSource.DEVICE, rowId.toString(),
                volumeName, volumeVersion, generationAdded)
        }
    }
}
