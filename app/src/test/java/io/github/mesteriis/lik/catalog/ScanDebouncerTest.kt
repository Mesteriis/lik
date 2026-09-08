package io.github.mesteriis.lik.catalog

import org.junit.Assert.*
import org.junit.Test

class ScanDebouncerTest {
    @Test fun burstRunsOnceAndStopDropsPendingWork() {
        val timers = mutableListOf<() -> Unit>()
        var scans = 0
        val debouncer = ScanDebouncer({ action ->
            timers += action
            { timers.remove(action); Unit }
        }) { scans++ }
        repeat(100) { debouncer.changed() }
        assertEquals(1, timers.size)
        timers.toList().also { timers.clear() }.forEach { it() }
        assertEquals(1, scans)
        debouncer.changed()
        debouncer.cancel()
        timers.toList().forEach { it() }
        assertEquals(1, scans)
    }
}
