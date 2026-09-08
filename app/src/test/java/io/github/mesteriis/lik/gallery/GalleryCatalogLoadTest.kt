package io.github.mesteriis.lik.gallery

import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryCatalogLoadTest {
    @Test fun nullMediaStoreQueryIsReportedAsASourceError() {
        val result = deviceQueryResult(includeDevicePhotos = true) { null }

        assertTrue(result.isFailure)
    }
}
