package io.github.mesteriis.lik.aigate

import org.junit.Assert.*
import org.junit.Test

class AiGateContractTest {
    @Test fun portValidationCreatesLoopbackOnlyEndpoints() {
        val endpoint = AiGateEndpoint(8889)
        assertEquals("http://127.0.0.1:8889/health", endpoint.url("/health").toString())
        assertThrows(IllegalArgumentException::class.java) { AiGateEndpoint(0) }
        assertThrows(IllegalArgumentException::class.java) { endpoint.url("http://elsewhere") }
    }

    @Test fun discoveryIsBoundedToRouterPortWindow() {
        assertEquals((8889..8909).toList(), AiGateEndpoint.discoveryPorts())
    }

    @Test fun modelListDoesNotClaimVisionCapability() {
        val model = AiGateModel("deepseek", ownedBy = "router")
        assertFalse(model.visionKnown)
    }

    @Test fun everyPhotoSendRequiresExplicitPerPhotoToken() {
        val consent = PhotoSendConsent()
        val token = consent.grant("media-1", 8)
        assertTrue(consent.consume(token, "media-1", 8))
        assertFalse(consent.consume(token, "media-1", 8))
        assertFalse(consent.consume(consent.grant("media-1", 8), "media-1", 9))
    }
}
