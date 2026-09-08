package io.github.mesteriis.lik.catalog

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Library dates never change merely because the phone travels to another timezone. Weeks are ISO Monday. */
@android.annotation.SuppressLint("UseKtx")
fun libraryZone(context: Context): ZoneId {
    val preferences = context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE)
    synchronized(CatalogZoneLock) {
        val stored = preferences.getString("gallery.library.zone", null)
        if (stored != null) return ZoneId.of(stored)
        return ZoneId.systemDefault().also {
            check(preferences.edit().putString("gallery.library.zone", it.id).commit())
        }
    }
}
private object CatalogZoneLock

fun MediaRecord.withPeriods(zone: ZoneId): MediaRecord {
    val instant = takenAt ?: addedAt
    val date = instant?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    return copy(sortAt = instant ?: Long.MIN_VALUE,
        dayKey = date?.toString() ?: "undated",
        weekKey = date?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))?.toString() ?: "undated",
        monthKey = date?.let { java.time.YearMonth.from(it).toString() } ?: "undated",
        yearKey = date?.year?.toString() ?: "undated")
}

data class PeriodSummary(val periodKey: String, val count: Int, val newestAt: Long)
