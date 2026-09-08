package io.github.mesteriis.lik.ai

import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileReader
import java.text.Normalizer

data class BertTokens(val ids: LongArray, val mask: LongArray)

class WordPieceTokenizer(private val vocabulary: Map<String, Int>) {
    private val unknown = vocabulary.getValue("[UNK]")
    fun encode(text: String, length: Int): BertTokens {
        require(length >= 2)
        val pieces = basicTokens(text).flatMap(::wordPieces).take(length - 2)
        val ids = LongArray(length)
        val mask = LongArray(length)
        ids[0] = vocabulary.getValue("[CLS]").toLong(); mask[0] = 1
        pieces.forEachIndexed { index, piece -> ids[index + 1] = vocabulary.getValue(piece).toLong(); mask[index + 1] = 1 }
        val end = pieces.size + 1; ids[end] = vocabulary.getValue("[SEP]").toLong(); mask[end] = 1
        return BertTokens(ids, mask)
    }

    private fun wordPieces(token: String): List<String> {
        if (token.codePointCount(0, token.length) > 100) return listOf("[UNK]")
        val result = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val candidate = (if (start == 0) "" else "##") + token.substring(start, end)
                if (candidate in vocabulary) { found = candidate; break }
                end = token.offsetByCodePoints(end, -1)
            }
            if (found == null) return listOf("[UNK]")
            result += found; start = end
        }
        return result
    }

    companion object {
        fun load(file: File) = WordPieceTokenizer(file.readLines().mapIndexed { id, token -> token to id }.toMap())
        private fun basicTokens(value: String): List<String> {
            val cleaned = buildString {
                val source = Normalizer.normalize(value, Normalizer.Form.NFC)
                var at = 0
                while (at < source.length) {
                    val cp = source.codePointAt(at); at += Character.charCount(cp)
                    when {
                        cp == 0 || cp == 0xfffd || Character.getType(cp) == Character.CONTROL.toInt() -> Unit
                        Character.isWhitespace(cp) -> append(' ')
                        isCjk(cp) -> { append(' '); appendCodePoint(cp); append(' ') }
                        else -> appendCodePoint(cp)
                    }
                }
            }
            return cleaned.trim().split(Regex("\\s+")).flatMap { token ->
                val result = mutableListOf<String>(); val current = StringBuilder()
                fun flush() { if (current.isNotEmpty()) { result += current.toString(); current.setLength(0) } }
                var at = 0
                while (at < token.length) { val cp = token.codePointAt(at); at += Character.charCount(cp)
                    if (isPunctuation(cp)) { flush(); result += String(Character.toChars(cp)) } else current.appendCodePoint(cp) }
                flush(); result
            }
        }
        private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2CEAF
        private fun isPunctuation(cp: Int): Boolean {
            val type = Character.getType(cp)
            return cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
                type in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
                    Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
        }
    }
}

class SigLipBpeTokenizer(private val vocabulary: Map<String, Int>, merges: List<Pair<String, String>>) {
    private val ranks = merges.withIndex().associate { it.value to it.index }
    private val unknown = vocabulary.getValue("<unk>")

    fun encode(text: String, length: Int): LongArray {
        require(length >= 1)
        val normalized = text.replace(" ", "▁")
        val symbols = mutableListOf<String>()
        var at = 0
        while (at < normalized.length) {
            val cp = normalized.codePointAt(at); at += Character.charCount(cp)
            val value = String(Character.toChars(cp))
            if (value in vocabulary) symbols += value else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                val fallback = bytes.map { "<0x%02X>".format(it.toInt() and 0xff) }
                if (fallback.all { it in vocabulary }) symbols += fallback else symbols += "<unk>"
            }
        }
        while (symbols.size > 1) {
            var bestIndex = -1; var bestRank = Int.MAX_VALUE
            for (index in 0 until symbols.lastIndex) {
                val rank = ranks[symbols[index] to symbols[index + 1]] ?: continue
                if (rank < bestRank) { bestRank = rank; bestIndex = index }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] += symbols[bestIndex + 1]; symbols.removeAt(bestIndex + 1)
        }
        val ids = symbols.flatMap { symbol ->
            vocabulary[symbol]?.let { listOf(it) } ?: buildList {
                symbol.toByteArray(Charsets.UTF_8).forEach { byte -> vocabulary["<0x%02X>".format(byte.toInt() and 0xff)]?.let(::add) }
            }.ifEmpty { listOf(unknown) }
        }.take(length - 1).toMutableList()
        ids += vocabulary.getValue("<eos>")
        return LongArray(length) { ids.getOrElse(it) { vocabulary.getValue("<pad>") }.toLong() }
    }

    companion object {
        fun load(file: File): SigLipBpeTokenizer {
            val vocabulary = HashMap<String, Int>(300_000)
            val merges = ArrayList<Pair<String, String>>(600_000)
            JsonReader(FileReader(file).buffered()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "model" -> { reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                        "vocab" -> { reader.beginObject(); while (reader.hasNext()) vocabulary[reader.nextName()] = reader.nextInt(); reader.endObject() }
                        "merges" -> { reader.beginArray(); while (reader.hasNext()) { reader.beginArray(); val left = reader.nextString(); val right = reader.nextString(); reader.endArray(); merges += left to right }; reader.endArray() }
                        else -> reader.skipValue()
                    }; reader.endObject() }
                    else -> reader.skipValue()
                }
                reader.endObject()
            }
            require(vocabulary.size >= 256_000 && merges.isNotEmpty())
            return SigLipBpeTokenizer(vocabulary, merges)
        }
    }
}
