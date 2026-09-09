package io.github.mesteriis.lik.similarity

import io.github.mesteriis.lik.catalog.MediaAvailability
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaSource
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class SimilarityRulesTest {
    @Test fun oneHundredThousandRoutineCatalogUpdatesDoNotChangeSimilarityDomain(){
        val stable=row("stable",MediaSource.DEVICE).copy(contentRevision=9,accessGrantEpoch=4)
        repeat(100_000){index->
            val bookkeeping=stable.copy(lastSeenAt=index.toLong(),scanMarker="scan-$index",displayName="Фото $index",takenAt=index.toLong(),modifiedAt=index.toLong())
            assertFalse(SimilarityDomainRevision.mediaChanged(stable,bookkeeping))
        }
    }

    @Test fun automaticLibraryWorkIsFiniteAtOneHundredThousandItems(){
        assertEquals(8,SimilarityBudgets.maximumAutomaticJobs())
        assertEquals(8192L,SimilarityBudgets.maximumAutomaticComparisons())
        assertEquals(0L,SimilarityBudgets.uniqueBucketComparisons(100_000,1))
        assertEquals(800_000L,SimilarityBudgets.maximumStoredVisualRelations(100_000))
    }

    @Test fun exactAndVisualPaginationAreIndependent(){
        val state=SimilarityPagingState(exactResultCount=61,visualResultCount=0,pageSize=60)
        assertTrue(state.hasNextExact);assertFalse(state.hasNextVisual)
    }
    @Test fun exactDigestIsIndependentOfCatalogIdentityAndStreamsAllBytes() {
        val bytes = ByteArray(170_003) { (it * 31).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        assertEquals(expected, ContentDigest.sha256(ByteArrayInputStream(bytes)))
        assertEquals(expected, ContentDigest.sha256(ByteArrayInputStream(bytes)))
    }

    @Test fun interruptedDigestDoesNotPublishPartialHash() {
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            ContentDigest.sha256(ByteArrayInputStream(ByteArray(200_000))) { ++checks > 1 }
        }
    }

    @Test fun perceptualFingerprintMatchesBrightnessEditAndSmallCropButRejectsDifferentImage() {
        val original = gradient(48, 40) { x, y -> x * 3 + y * 4 + if (x in 12..31 && y in 10..29) 70 else 0 }
        val brighter = original.map { (it + 22).coerceAtMost(255) }.toIntArray()
        val crop = crop(original, 48, 40, 2, 2, 44, 36)
        val different = gradient(48, 40) { x, y -> if ((x / 5 + y / 5) % 2 == 0) 10 else 245 }
        val a = PerceptualFingerprintV2.fromLuma(48, 40, original)
        val b = PerceptualFingerprintV2.fromLuma(48, 40, brighter)
        val c = PerceptualFingerprintV2.fromLuma(44, 36, crop)
        val d = PerceptualFingerprintV2.fromLuma(48, 40, different)
        assertTrue(PerceptualFingerprintV2.distance(a, b) <= PerceptualFingerprintV2.SIMILAR_DISTANCE)
        assertTrue(PerceptualFingerprintV2.distance(a, c) <= PerceptualFingerprintV2.SIMILAR_DISTANCE)
        assertTrue(PerceptualFingerprintV2.distance(a, d) > PerceptualFingerprintV2.SIMILAR_DISTANCE)
    }

    @Test fun thresholdIsInclusiveAndPairOrderCannotCreateDuplicates() {
        assertTrue(SimilarityRules.isSimilar(PerceptualFingerprintV2.SIMILAR_DISTANCE))
        assertFalse(SimilarityRules.isSimilar(PerceptualFingerprintV2.SIMILAR_DISTANCE + 1))
        assertEquals(MediaPair("a", "z"), MediaPair.ordered("z", "a"))
        assertThrows(IllegalArgumentException::class.java) { MediaPair.ordered("a", "a") }
    }

    @Test fun multiIndexBandsProvablyRetainEveryHashWithinDistanceFourteen() {
        val original=ByteArray(8)
        val oneBitPerByte=original.copyOf().also{for(index in it.indices)it[index]=(1 shl (index%8)).toByte()}
        val fourteenDifferentChunks=original.copyOf().also{bits->for(chunk in 0 until 14){val byte=chunk/2;val shift=(chunk%2)*4;bits[byte]=(bits[byte].toInt() or (1 shl shift)).toByte()}}
        val unrelated=ByteArray(8){0xff.toByte()}
        assertEquals(16,FingerprintBands.keys(original).size)
        assertEquals(8,PerceptualFingerprintV2.distance(original,oneBitPerByte))
        assertTrue(FingerprintBands.keys(original).intersect(FingerprintBands.keys(oneBitPerByte)).isNotEmpty())
        assertEquals(14,PerceptualFingerprintV2.distance(original,fourteenDifferentChunks))
        assertTrue(FingerprintBands.keys(original).intersect(FingerprintBands.keys(fourteenDifferentChunks)).isNotEmpty())
        assertTrue(FingerprintBands.keys(original).intersect(FingerprintBands.keys(unrelated)).isEmpty())
    }

    @Test fun scaleBudgetsBoundEveryTransactionRunAndStoredVisualEdges() {
        assertTrue(SimilarityBudgets.CANDIDATE_PAGE <= SimilarityBudgets.CANDIDATES_PER_ITEM_STEP)
        assertTrue(SimilarityBudgets.CANDIDATES_PER_ITEM_STEP <= SimilarityBudgets.CANDIDATES_PER_RUN)
        assertEquals(800_000L,SimilarityBudgets.maximumStoredVisualRelations(100_000))
        assertEquals(8,SimilarityBudgets.maximumCandidatePagesPerRun())
    }

    @Test fun actionCapabilitiesNeverDeleteDeviceOriginalAndRequireCurrentSafeImportedCopy() {
        val device = row("device", MediaSource.DEVICE)
        val imported = row("import", MediaSource.GOOGLE_IMPORT).copy(privateFileId = "a".repeat(64))
        assertEquals(ComparisonCapabilities(canCompare = true, canMoveToTrash = false), ComparisonCapabilities.forMedia(device, safe = true))
        assertEquals(ComparisonCapabilities(canCompare = true, canMoveToTrash = true), ComparisonCapabilities.forMedia(imported, safe = true))
        assertFalse(ComparisonCapabilities.forMedia(imported.copy(availability = MediaAvailability.INACCESSIBLE), safe = true).canCompare)
        assertFalse(ComparisonCapabilities.forMedia(imported, safe = false).canCompare)
    }

    @Test fun relationPublicationRejectsRevisionAccessTrashAndUnsafeInputs() {
        val current = row("m", MediaSource.DEVICE).copy(contentRevision = 8, accessGrantEpoch = 3)
        val token = FingerprintToken("m", 8, 3)
        assertTrue(SimilarityRules.canPublish(current, safe = true, token))
        assertFalse(SimilarityRules.canPublish(current.copy(contentRevision = 9), safe = true, token))
        assertFalse(SimilarityRules.canPublish(current.copy(accessGrantEpoch = 4), safe = true, token))
        assertFalse(SimilarityRules.canPublish(current.copy(availability = MediaAvailability.TRASHED), safe = true, token))
        assertFalse(SimilarityRules.canPublish(current, safe = false, token))
    }

    private fun row(id:String, source:MediaSource)=MediaRecord(id,source,id,contentUri="content://$id",lastSeenAt=1)
    private fun gradient(w:Int,h:Int,value:(Int,Int)->Int)=IntArray(w*h){i->value(i%w,i/w).coerceIn(0,255)}
    private fun crop(p:IntArray,w:Int,h:Int,x0:Int,y0:Int,cw:Int,ch:Int)=IntArray(cw*ch){i->p[(y0+i/cw).coerceAtMost(h-1)*w+(x0+i%cw).coerceAtMost(w-1)]}
    private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
}
