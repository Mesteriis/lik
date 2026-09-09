package io.github.mesteriis.lik.ai

import java.nio.charset.StandardCharsets
import java.util.UUID

internal object LegacyPersonIdentity {
    fun stableId(legacyId: String): String {
        require(legacyId.startsWith("auto:"))
        return UUID.nameUUIDFromBytes("lik-person-v10:$legacyId".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
