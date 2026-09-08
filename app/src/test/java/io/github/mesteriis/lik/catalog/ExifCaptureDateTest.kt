package io.github.mesteriis.lik.catalog

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ExifCaptureDateTest {
    @Test fun offsetlessCaptureUsesLibraryZoneAcrossDstOverlap() {
        assertEquals(1792888200000L, ExifCaptureDate.parse("2026:10:25 02:30:00", null, ZoneId.of("Europe/Madrid"))?.instant)
        assertNull(ExifCaptureDate.parse("2026:10:25 02:30:00", null, ZoneId.of("Europe/Madrid"))?.offset)
    }
    @Test fun explicitOffsetWinsAndMalformedDateStaysUnknown() {
        assertEquals(1792891800000L, ExifCaptureDate.parse("2026:10:25 02:30:00", "+01:00", ZoneId.of("America/New_York"))?.instant)
        assertEquals(3600, ExifCaptureDate.parse("2026:10:25 02:30:00", "+01:00", ZoneId.of("UTC"))?.offset)
        assertNull(ExifCaptureDate.parse("broken", null, ZoneId.of("UTC")))
    }
}
