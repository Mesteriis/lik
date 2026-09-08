package io.github.mesteriis.lik.catalog

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OrganizationRulesTest {
    @Test fun eachOperationUsesSourceAndCurrentAvailability() {
        val device = MediaRecord("d", MediaSource.DEVICE, "1", contentUri = "content://media/1", lastSeenAt = 1)
        val imported = MediaRecord("i", MediaSource.GOOGLE_IMPORT, "i", privateFileId = "i", lastSeenAt = 1)
        assertTrue(device.allows(MediaOperation.ORGANIZE))
        assertTrue(device.allows(MediaOperation.SHARE))
        assertTrue(device.allows(MediaOperation.EXPORT))
        assertFalse(device.allows(MediaOperation.DELETE_COPY))
        assertTrue(imported.allows(MediaOperation.DELETE_COPY))
        for (operation in MediaOperation.entries) {
            assertFalse(device.copy(availability = MediaAvailability.INACCESSIBLE).allows(operation))
            assertFalse(imported.copy(availability = MediaAvailability.MISSING).allows(operation))
        }
        assertFalse(imported.copy(privateFileId = null).allows(MediaOperation.DELETE_COPY))
    }
    @Test fun unicodeKeysPreserveCyrillicAndNormalizeCanonicalEquivalentsIndependentlyOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("ёлка i", searchKey("Е\u0308ЛКА I"))
            assertNotEquals(searchKey("е"), searchKey("ё"))
        } finally { Locale.setDefault(previous) }
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDateRangeIsRejected() { CatalogSearch(from = 2, until = 1) }
}
