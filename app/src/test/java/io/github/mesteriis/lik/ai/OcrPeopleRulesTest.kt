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

    @Test fun productionDetectorOutputUsesBoundedBulkContract() {
        val floats = 1024 * 960
        assertTrue(FloatIpcContract.bytes(floats) > FloatIpcContract.BINDER_INLINE_LIMIT_BYTES)
        assertTrue(floats <= FloatIpcContract.MAX_OUTPUT_FLOATS)
    }

    @Test fun detectorProducesSlantedDbQuadrilateralAndUnclipsIt() {
        val width=20;val height=16;val map=FloatArray(width*height)
        for(y in 3..9) for(x in (y-1)..(y+5)) map[y*width+x]=.92f
        val quad=DbRegions.quadrilaterals(map,width,height).single()
        assertEquals(4,quad.points.size)
        assertTrue(quad.score>=.9f)
        assertTrue(quad.points.zipWithNext().any { (a,b) -> kotlin.math.abs(a.y-b.y)>.01f && kotlin.math.abs(a.x-b.x)>.01f })
        assertTrue((quad.box.right-quad.box.left) > 7f/width)
    }

    @Test fun fiveLandmarkSimilarityAlignmentUsesAllPinnedPoints() {
        val target=floatArrayOf(38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f)
        val source=FloatArray(10){i->if(i%2==0)(target[i]-7f)/1.2f else (target[i]+4f)/1.2f}
        val transform=SimilarityTransform.estimate(source,target)
        for(i in 0 until 5){val mapped=transform.map(source[i*2],source[i*2+1]);assertEquals(target[i*2],mapped.x,.001f);assertEquals(target[i*2+1],mapped.y,.001f)}
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
