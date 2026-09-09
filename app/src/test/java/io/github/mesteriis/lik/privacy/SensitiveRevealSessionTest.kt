package io.github.mesteriis.lik.privacy

import org.junit.Assert.*
import org.junit.Test

class SensitiveRevealSessionTest {
    @Test fun successfulStrongAuthenticationCreatesMemoryOnlyLease() {
        val session = SensitiveRevealSession()
        val request = session.beginAuthentication()
        assertTrue(session.authenticationSucceeded(request, AuthStrength.STRONG))
        assertTrue(session.snapshot().revealed)
        assertFalse(session.authenticationSucceeded(request, AuthStrength.DEVICE_CREDENTIAL))
    }

    @Test fun backgroundAndScreenLockInvalidatePendingAndPublishedWork() {
        val session = SensitiveRevealSession()
        val request = session.beginAuthentication()
        val before = session.snapshot().epoch
        session.relock(RevealRelockReason.BACKGROUND)
        assertFalse(session.authenticationSucceeded(request, AuthStrength.STRONG))
        assertFalse(session.accepts(before))
        val second = session.beginAuthentication()
        assertTrue(session.authenticationSucceeded(second, AuthStrength.STRONG))
        val revealed = session.snapshot().epoch
        session.relock(RevealRelockReason.SCREEN_LOCK)
        assertFalse(session.accepts(revealed))
    }

    @Test fun cancelErrorLockoutAndUnavailableStayHidden() {
        AuthFailure.values().forEach { failure ->
            val session = SensitiveRevealSession()
            val request = session.beginAuthentication()
            session.authenticationFailed(request, failure)
            assertFalse(session.snapshot().revealed)
            assertFalse(session.authenticationSucceeded(request, AuthStrength.STRONG))
        }
    }

    @Test fun staleTerminalCallbackCannotCloseAnewerAuthenticatedLease() {
        val session = SensitiveRevealSession()
        val old = session.beginAuthentication()
        val current = session.beginAuthentication()
        assertTrue(session.authenticationSucceeded(current, AuthStrength.STRONG))
        val epoch = session.snapshot().epoch
        session.authenticationFailed(old, AuthFailure.ERROR)
        assertTrue(session.accepts(epoch))
    }

    @Test fun aGateNeedsRevealEpochAndSeparatePerPhotoConsent() {
        val session = SensitiveRevealSession()
        val request = session.beginAuthentication()
        session.authenticationSucceeded(request, AuthStrength.STRONG)
        val reveal = session.snapshot()
        val consent = SensitiveSendConsent.grant("photo", 5, reveal)
        assertTrue(consent.accepts("photo", 5, reveal))
        assertFalse(consent.accepts("other", 5, reveal))
        session.relock(RevealRelockReason.EXPLICIT)
        assertFalse(consent.accepts("photo", 5, session.snapshot()))
    }
}
