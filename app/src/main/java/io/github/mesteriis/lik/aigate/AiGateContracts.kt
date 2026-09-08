package io.github.mesteriis.lik.aigate

import java.net.URI
import java.util.UUID

data class AiGateEndpoint(val port: Int) {
    init { require(port in 1..65535) }
    fun url(path: String): URI {
        require(path.startsWith('/') && !path.startsWith("//") && ':' !in path)
        return URI("http://127.0.0.1:$port$path")
    }
    companion object { fun discoveryPorts() = (8889..8909).toList() }
}

data class AiGateModel(val id: String, val ownedBy: String?, val visionKnown: Boolean = false)
class PhotoSendToken internal constructor(val value: String, val mediaId: String, val revision: Long)

/** Memory-only, single-use authorization; never persisted or inferred from a prior send. */
class PhotoSendConsent {
    private val tokens = mutableSetOf<String>()
    @Synchronized fun grant(mediaId: String, revision: Long) = PhotoSendToken(UUID.randomUUID().toString(), mediaId, revision).also { tokens += it.value }
    @Synchronized fun consume(token: PhotoSendToken, mediaId: String, revision: Long): Boolean =
        token.mediaId == mediaId && token.revision == revision && tokens.remove(token.value)
    @Synchronized fun clear() { tokens.clear() }
}
