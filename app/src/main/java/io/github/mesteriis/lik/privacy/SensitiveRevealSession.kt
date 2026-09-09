package io.github.mesteriis.lik.privacy

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

enum class AuthStrength { STRONG, WEAK, DEVICE_CREDENTIAL }
enum class AuthFailure { CANCELLED, ERROR, LOCKOUT, UNAVAILABLE }
enum class RevealRelockReason { BACKGROUND, SCREEN_LOCK, USER_SWITCH, PROCESS_START, CONTENT_CHANGED, EXPLICIT }

data class RevealSnapshot(val revealed: Boolean, val epoch: Long)

class SensitiveRevealSession {
    private val counter = AtomicLong(1)
    @Volatile private var state = RevealSnapshot(false, 1)
    @Volatile private var pendingRequest: Long? = null
    private val listeners = CopyOnWriteArraySet<(RevealSnapshot) -> Unit>()

    @Synchronized fun beginAuthentication(): Long = counter.incrementAndGet().also { pendingRequest = it }

    @Synchronized fun authenticationSucceeded(request: Long, strength: AuthStrength): Boolean {
        if (pendingRequest != request) return false
        if (strength != AuthStrength.STRONG) {
            pendingRequest = null
            state = RevealSnapshot(false, counter.incrementAndGet())
            listeners.forEach { it(state) }
            return false
        }
        pendingRequest = null
        state = RevealSnapshot(true, counter.incrementAndGet())
        listeners.forEach { it(state) }
        return true
    }

    @Synchronized fun authenticationFailed(request: Long, failure: AuthFailure) {
        if (pendingRequest != request) return
        pendingRequest = null
        // Every terminal result keeps the boundary closed and advances its callback epoch.
        state = RevealSnapshot(false, counter.incrementAndGet())
        listeners.forEach { it(state) }
    }

    @Synchronized fun relock(reason: RevealRelockReason) {
        pendingRequest = null
        state = RevealSnapshot(false, counter.incrementAndGet())
        listeners.forEach { it(state) }
    }

    fun snapshot(): RevealSnapshot = state
    fun accepts(epoch: Long): Boolean = state.revealed && state.epoch == epoch
    fun observe(listener: (RevealSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }
}

/** Process-local singleton: process death necessarily returns to the locked state. */
object SensitiveMediaSession {
    val current = SensitiveRevealSession()
}

class SensitiveSendConsent private constructor(
    val mediaId: String,
    val contentRevision: Long,
    private val revealEpoch: Long,
) {
    fun accepts(id: String, revision: Long, reveal: RevealSnapshot): Boolean =
        reveal.revealed && reveal.epoch == revealEpoch && id == mediaId && revision == contentRevision

    companion object {
        fun grant(mediaId: String, revision: Long, reveal: RevealSnapshot): SensitiveSendConsent {
            require(mediaId.isNotBlank() && revision >= 0 && reveal.revealed)
            return SensitiveSendConsent(mediaId, revision, reveal.epoch)
        }
    }
}
