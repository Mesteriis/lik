package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class DownloadContractTest {
    private val artifact = ArtifactSpec("c/model.onnx", 100, "a".repeat(64), URI("https://huggingface.co/o/r/resolve/${"b".repeat(40)}/model.onnx"))

    @Test fun resumeAcceptsOnlyMatching206Range() {
        assertEquals(DownloadDecision.APPEND, DownloadProtocol.response(artifact, 40, 206, "bytes 40-99/100", 60, artifact.url))
        assertEquals(DownloadDecision.RESTART, DownloadProtocol.response(artifact, 40, 200, null, 100, artifact.url))
        assertEquals(DownloadDecision.REJECT, DownloadProtocol.response(artifact, 40, 206, "bytes 0-59/100", 60, artifact.url))
    }

    @Test fun redirectsCannotLeavePinnedHuggingFaceRevision() {
        assertTrue(DownloadProtocol.allowedRedirect(artifact.url, URI("https://cdn-lfs.huggingface.co/signed/object")))
        assertFalse(DownloadProtocol.allowedRedirect(artifact.url, URI("https://evil.invalid/model")))
        assertFalse(DownloadProtocol.allowedRedirect(artifact.url, URI("http://huggingface.co/model")))
    }

    @Test fun spaceReservationUsesMissingBytesPlusSafetyMarginWithoutOverflow() {
        assertEquals(1_048_676L, SpaceReservation.required(listOf(artifact), emptySet(), 1_048_576))
        assertThrows(IllegalArgumentException::class.java) { SpaceReservation.required(listOf(artifact.copy(size = Long.MAX_VALUE)), emptySet(), 1) }
    }

    @Test fun journalRecoveryNeverPublishesUnverifiedBytes() {
        assertEquals(RecoveryAction.RESUME, DownloadRecovery.action(DownloadJournalStage.DOWNLOADING, 50, 100, false))
        assertEquals(RecoveryAction.VERIFY, DownloadRecovery.action(DownloadJournalStage.VERIFYING, 100, 100, false))
        assertEquals(RecoveryAction.PUBLISH, DownloadRecovery.action(DownloadJournalStage.VERIFIED, 100, 100, true))
        assertEquals(RecoveryAction.DISCARD, DownloadRecovery.action(DownloadJournalStage.VERIFIED, 99, 100, true))
    }

    @Test fun reservationCountsOnlyRemainingSharedBytesAndHasSingleDigestOwner() {
        val second = artifact.copy(path = "shared/model.onnx", size = 200, sha256 = "c".repeat(64))
        val plan = DownloadReservationPlan.create("compact", listOf(artifact to 40L, second to 200L), 10)
        assertEquals(70, plan.requiredBytes)
        assertEquals(setOf(artifact.sha256), plan.remainingByDigest.keys)
        val ownership = DownloadOwnership()
        assertTrue(ownership.claim("compact", artifact.sha256))
        assertFalse(ownership.claim("balanced", artifact.sha256))
        ownership.release("compact", artifact.sha256)
        assertTrue(ownership.claim("balanced", artifact.sha256))
    }
}
