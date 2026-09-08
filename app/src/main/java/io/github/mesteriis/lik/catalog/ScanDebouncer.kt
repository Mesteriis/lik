package io.github.mesteriis.lik.catalog

/** Owner-thread debounce. Cancelling the timer also guards callbacks that were already dequeued. */
class ScanDebouncer(private val schedule: (() -> Unit) -> (() -> Unit), private val scan: () -> Unit) {
    private var cancelTimer: (() -> Unit)? = null
    private var revision = 0L
    fun changed() {
        cancel()
        val ticket = revision
        cancelTimer = schedule { if (ticket == revision) { cancelTimer = null; scan() } }
    }
    fun cancel() { revision++; cancelTimer?.invoke(); cancelTimer = null }
}
