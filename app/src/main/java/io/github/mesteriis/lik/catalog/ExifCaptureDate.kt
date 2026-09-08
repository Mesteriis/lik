package io.github.mesteriis.lik.catalog

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class ExifCaptureDate(val instant: Long, val offset: Int?) {
    companion object {
        fun parse(date: String?, offset: String?, libraryZone: ZoneId): ExifCaptureDate? = runCatching {
            val local = LocalDateTime.parse(date ?: return null,
                DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
            val explicit = offset?.let(ZoneOffset::of)
            // Offsetless EXIF is a library-local wall time; Java resolves an overlap to its earlier offset.
            ExifCaptureDate((explicit?.let(local::toInstant) ?: local.atZone(libraryZone).toInstant()).toEpochMilli(), explicit?.totalSeconds)
        }.getOrNull()
    }
}
