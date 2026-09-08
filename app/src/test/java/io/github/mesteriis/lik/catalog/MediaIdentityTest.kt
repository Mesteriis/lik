package io.github.mesteriis.lik.catalog

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    @Test fun importedIdentityPreservesShaAndCannotCollideWithDeviceIdentity() {
        val sha = "a".repeat(64)
        assertEquals(sha, MediaIdentity.imported(sha).mediaId)
        assertNotEquals(sha, MediaIdentity.device("external_primary", "v1", 10, 1).mediaId)
    }

    @Test fun sameDeviceObjectKeepsIdentityAcrossMetadataChanges() {
        val first = MediaIdentity.device("external_primary", "v1", 10, 1)
        assertEquals(first, MediaIdentity.device("external_primary", "v1", 10, 1))
    }

    @Test fun reusedRowAcrossVolumesVersionsOrBirthGenerationsIsAnotherObject() {
        val ids = listOf(
            MediaIdentity.device("external_primary", "v1", 10, 1),
            MediaIdentity.device("0123-4567", "v1", 10, 1),
            MediaIdentity.device("external_primary", "v2", 10, 1),
            MediaIdentity.device("external_primary", "v1", 10, 2),
            MediaIdentity.device("external_primary", "v1", 11, 1),
        ).map { it.mediaId }
        assertEquals(5, ids.toSet().size)
    }

    @Test fun identityEncodingDoesNotConfuseDelimiters() {
        assertNotEquals(
            MediaIdentity.device("a:b", "c", 1, 1).mediaId,
            MediaIdentity.device("a", "b:c", 1, 1).mediaId,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun privateIdentityRejectsPaths() { MediaIdentity.imported("../photo") }
}
