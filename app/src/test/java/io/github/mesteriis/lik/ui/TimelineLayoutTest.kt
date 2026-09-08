package io.github.mesteriis.lik.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLayoutTest {
    @Test fun asymmetricDayPatternFillsEveryNarrowRow() {
        assertEquals(listOf(2, 1, 1, 1, 1, 1, 2), (0..6).map { daySpanUnits(it, 3) })
        assertEquals(listOf(3), filledRows((0..20).map { daySpanUnits(it, 3) }, 3).distinct())
    }

    @Test fun asymmetricDayPatternFillsEveryWideRow() {
        assertEquals(listOf(2, 1, 1, 1, 1, 2, 1, 1, 1, 1), (0..9).map { daySpanUnits(it, 4) })
        assertEquals(listOf(4), filledRows((0..29).map { daySpanUnits(it, 4) }, 4).distinct())
    }

    @Test fun finalTileFillsTheRemainingWidthOfEachDay() {
        (1..12).forEach { size ->
            val spans = (0 until size).map { daySpanUnits(it, 3, size) }
            assertEquals("day size $size", listOf(3), filledRows(spans, 3).distinct())
            assertEquals("day size $size has no unfinished row", 0, spans.sum() % 3)
        }
    }

    private fun filledRows(units: List<Int>, columns: Int): List<Int> {
        val rows = mutableListOf<Int>()
        var row = 0
        units.forEach { span ->
            if (row + span > columns) { rows += row; row = 0 }
            row += span
            if (row == columns) { rows += row; row = 0 }
        }
        return rows
    }
}
