package io.github.mesteriis.lik.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.Assume.assumeTrue
import com.google.gson.JsonParser
import java.io.File

class TextTokenizerTest {
    @Test fun wordPiecePreservesCyrillicAndUsesGreedyContinuation() {
        val tokenizer = WordPieceTokenizer(mapOf("[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102,
            "Фото" to 10, "кот" to 11, "##а" to 12, "!" to 13))
        assertArrayEquals(longArrayOf(101, 10, 11, 12, 13, 102, 0, 0), tokenizer.encode("Фото кота!", 8).ids)
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 1, 1, 0, 0), tokenizer.encode("Фото кота!", 8).mask)
    }

    @Test fun siglipBpeAppliesLowestRankMergeThenEosAndPadding() {
        val tokenizer = SigLipBpeTokenizer(mapOf("<pad>" to 0, "<eos>" to 1, "<unk>" to 3,
            "▁" to 10, "к" to 11, "о" to 12, "т" to 13, "ко" to 20, "кот" to 21),
            listOf("к" to "о", "ко" to "т"))
        assertArrayEquals(longArrayOf(10, 21, 1, 0, 0), tokenizer.encode(" кот", 5))
    }

    @Test fun siglipBpeUsesUtf8ByteFallback() {
        val tokenizer = SigLipBpeTokenizer(mapOf("<pad>" to 0, "<eos>" to 1, "<unk>" to 3,
            "<0xF0>" to 40, "<0x9F>" to 41, "<0x98>" to 42, "<0x80>" to 43), emptyList())
        assertArrayEquals(longArrayOf(40, 41, 42, 43, 1), tokenizer.encode("😀", 5))
    }

    @Test fun downloadedTokenizersMatchPinnedEnglishAndRussianGoldens() {
        val cache = File(System.getenv("LIK_MODEL_CACHE") ?: File(System.getProperty("user.home"), "Library/Caches/Lik/model-artifacts").path, "hf-runtime")
        val golden = listOf(File("models/evaluation/tokenizer-golden-v1.json"), File("../models/evaluation/tokenizer-golden-v1.json"))
            .map(File::getCanonicalFile).firstOrNull(File::isFile) ?: File("missing")
        assumeTrue(cache.isDirectory && golden.isFile)
        val fixtures = JsonParser.parseReader(golden.reader()).asJsonObject["fixtures"].asJsonArray
        val compactCases = fixtures[0].asJsonObject["cases"].asJsonArray
        val siglipCases = fixtures[1].asJsonObject["cases"].asJsonArray
        val compact = WordPieceTokenizer.load(File(cache, "multilingual-text-v1/vocab.txt"))
        val siglip = SigLipBpeTokenizer.load(File(cache, "siglip2-tokenizer-v1/tokenizer.json"))
        for (index in 0 until compactCases.size()) {
            val case = compactCases[index].asJsonObject
            val expected = case["input_ids"].asJsonArray.map { it.asLong }.toLongArray()
            assertArrayEquals(case["id"].asString, expected, compact.encode(case["text"].asString, 128).ids)
        }
        for (index in 0 until siglipCases.size()) {
            val case = siglipCases[index].asJsonObject
            val expected = case["input_ids"].asJsonArray.map { it.asLong }.toLongArray()
            assertArrayEquals(case["id"].asString, expected, siglip.encode(case["text"].asString, 64))
        }
    }
}
