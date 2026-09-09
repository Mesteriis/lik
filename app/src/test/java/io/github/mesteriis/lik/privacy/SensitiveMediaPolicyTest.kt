package io.github.mesteriis.lik.privacy

import org.junit.Assert.*
import org.junit.Test

class SensitiveMediaPolicyTest {
    @Test fun quarantineIsDefaultAndOnlyExplicitSafeIsVisibleWhileLocked() {
        assertEquals(SensitiveDecision.QUARANTINED, SensitiveMediaPolicy.resolve(null, null, 7, 3))
        assertFalse(SensitiveMediaPolicy.mayReveal(SensitiveDecision.QUARANTINED, false))
        assertFalse(SensitiveMediaPolicy.mayReveal(SensitiveDecision.SENSITIVE, false))
        assertTrue(SensitiveMediaPolicy.mayReveal(SensitiveDecision.SAFE, false))
        assertTrue(SensitiveMediaPolicy.mayReveal(SensitiveDecision.SENSITIVE, true))
    }

    @Test fun manualOverrideIsAuthoritativeOnlyForItsContentRevision() {
        val automatic = AutomaticSensitiveDecision("m", 9, 4, SensitiveDecision.SENSITIVE)
        assertEquals(SensitiveDecision.SAFE, SensitiveMediaPolicy.resolve(automatic,
            ManualSensitiveOverride(9, SensitiveDecision.SAFE), 9, 4))
        assertEquals(SensitiveDecision.SENSITIVE, SensitiveMediaPolicy.resolve(automatic,
            ManualSensitiveOverride(8, SensitiveDecision.SAFE), 9, 4))
        assertEquals(SensitiveDecision.QUARANTINED, SensitiveMediaPolicy.resolve(automatic, null, 9, 5))
    }

    @Test fun rawClassifierOutputNeverCreatesAnAutomaticSafeDecisionWithoutCalibration() {
        assertEquals(SensitiveDecision.QUARANTINED,
            SensitiveMediaPolicy.fromClassifier(floatArrayOf(8f, -8f), releaseThreshold = null))
        assertEquals(SensitiveDecision.QUARANTINED,
            SensitiveMediaPolicy.fromClassifier(floatArrayOf(-8f, 8f), releaseThreshold = null))
        assertEquals(SensitiveDecision.QUARANTINED,
            SensitiveMediaPolicy.fromClassifier(floatArrayOf(Float.NaN, 0f), releaseThreshold = .9f))
    }
}
