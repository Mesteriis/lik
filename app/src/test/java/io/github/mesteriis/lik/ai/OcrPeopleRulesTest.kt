package io.github.mesteriis.lik.ai

import org.junit.Assert.*
import org.junit.Test

class OcrPeopleRulesTest {
    @Test fun ocrNormalizationPreservesCyrillicAndMatchesCanonicalUnicode() {
        val display = "  Ёлка\nCafe\u0301  42  "
        assertEquals("Ёлка\nCafé 42", OcrText.normalizeDisplay(display))
        assertEquals("ёлка café 42", OcrText.searchKey(display))
        assertTrue(OcrText.matches(display, "ЁЛКА café"))
    }

    @Test fun ctcDecoderRemovesBlankAndAdjacentRepeatsButPreservesRepeatedLetters() {
        val dictionary = listOf("а", "б", "A", " ")
        val logits = Array(8) { FloatArray(5) { -10f } }
        listOf(1, 1, 0, 2, 0, 2, 3, 4).forEachIndexed { at, id -> logits[at][id] = 10f }
        val decoded = CtcDecoder.decode(logits, dictionary)
        assertEquals("аббA ", decoded.text)
        assertTrue(decoded.confidence > .99f)
    }

    @Test fun detectorPostprocessBuildsOrderedRegionsAndRejectsWeakNoise() {
        val map=FloatArray(8*6);for(y in 1..2)for(x in 1..3)map[y*8+x]=.9f
        map[5*8+7]=.4f
        val regions=DbRegions.rectangles(map,8,6,800,600)
        assertEquals(1,regions.size)
        assertTrue(regions.single().left < regions.single().right)
    }

    @Test fun publicationRejectsRevisionEpochRevocationAndSensitiveQuarantine() {
        val token = AiPublicationToken("m", 7, 4, "pipe", "gen")
        assertTrue(token.matches("m", 7, 4, available = true, exposure = AiExposure.SAFE))
        assertFalse(token.matches("m", 8, 4, available = true, exposure = AiExposure.SAFE))
        assertFalse(token.matches("m", 7, 5, available = true, exposure = AiExposure.SAFE))
        assertFalse(token.matches("m", 7, 4, available = false, exposure = AiExposure.SAFE))
        assertFalse(token.matches("m", 7, 4, available = true, exposure = AiExposure.QUARANTINED))
    }

    @Test fun faceAnchorIsDeterministicAndStableAcrossModelGenerations() {
        val first = FaceAnchor.from("photo", FaceBox(.101f, .202f, .301f, .402f))
        val jitter = FaceAnchor.from("photo", FaceBox(.1012f, .2018f, .3009f, .4021f))
        assertEquals(first, jitter)
        assertNotEquals(first, FaceAnchor.from("other", FaceBox(.101f, .202f, .301f, .402f)))
    }

    @Test fun clusteringIsDeterministicAndHonorsManualCannotLink() {
        val faces = listOf(
            FaceVector("a", floatArrayOf(1f, 0f)),
            FaceVector("b", floatArrayOf(.99f, .01f)),
            FaceVector("c", floatArrayOf(0f, 1f)),
        )
        val normal = FaceClusterer.cluster(faces, .9f, emptySet())
        assertEquals(listOf(listOf("a", "b"), listOf("c")), normal)
        val split = FaceClusterer.cluster(faces.reversed(), .9f, setOf(ManualFacePair.ordered("a", "b")))
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), split)
    }

    @Test fun manualAssignmentsRemainAuthoritativeAfterReindex() {
        val decisions = ManualPeopleState()
            .assign("anchor-a", "person-1")
            .exclude("anchor-b")
            .cannotLink("anchor-c", "anchor-d")
        val computed = listOf(
            ComputedFace("new-generation-1", "anchor-a", "automatic-x"),
            ComputedFace("new-generation-2", "anchor-b", "automatic-x"),
            ComputedFace("new-generation-3", "anchor-c", "automatic-x"),
        )
        val resolved = PeopleResolution.apply(computed, decisions)
        assertEquals("person-1", resolved.single { it.anchorId == "anchor-a" }.personId)
        assertTrue(resolved.single { it.anchorId == "anchor-b" }.excluded)
        assertEquals("automatic-x", resolved.single { it.anchorId == "anchor-c" }.personId)
    }

    @Test fun mergeMoveSplitAndUndoDoNotMutateComputedClusters() {
        val original = listOf(ComputedFace("d1", "a", "auto"), ComputedFace("d2", "b", "auto"))
        var state = ManualPeopleState().assign("a", "alice").assign("b", "bob")
        state = state.merge("bob", "alice")
        assertEquals(setOf("alice"), PeopleResolution.apply(original, state).map { it.personId }.toSet())
        state = state.unmerge("bob").assign("b", "bob").exclude("a")
        val resolved = PeopleResolution.apply(original, state)
        assertTrue(resolved.single { it.anchorId == "a" }.excluded)
        assertEquals("bob", resolved.single { it.anchorId == "b" }.personId)
        assertEquals(listOf("auto", "auto"), original.map { it.computedClusterId })
    }
}
