package com.yugentech.quill.aira.rag

import java.text.Normalizer
import java.util.Locale

/** Whitespace-folded text with a reversible map; DOM coordinates always stay in original UTF-16. */
class TextIndex(private val original: String, check: () -> Unit = {}) {
    val text: String
    private val starts: IntArray?
    internal val offsetStorageSize: Int get() = starts?.size ?: 0
    private val size: Int
    init {
        val result = StringBuilder(original.length)
        var offsets: IntArray? = null
        var count = 0
        var i = 0
        while (i < original.length) {
            if (i % 4096 == 0) check()
            val start = i
            val ch = original[i++]
            if (space(ch)) {
                result.append(' ')
                while (i < original.length && space(original[i])) { if (i % 4096 == 0) check(); i++ }
                if (i - start > 1 && offsets == null) offsets = IntArray(original.length) { position ->
                    if (position % 4096 == 0) check()
                    position
                }
            } else result.append(ch)
            offsets?.set(count, start)
            count++
        }
        text = result.toString()
        starts = offsets
        size = count
    }
    fun originalStart(index: Int): Int = starts?.get(index) ?: index
    fun originalEnd(index: Int): Int {
        var end = originalStart(index) + 1
        if (text[index] == ' ') while (end < original.length && space(original[end])) end++
        return end
    }
    fun lowerBound(offset: Int): Int {
        val mapping = starts ?: return offset.coerceIn(0, size)
        val found = mapping.binarySearch(offset, 0, size)
        return if (found >= 0) found else -found - 1
    }
    companion object {
        private val marks = Regex("\\p{M}+")
        fun space(ch: Char) = Character.isWhitespace(ch) || Character.isSpaceChar(ch)
        private fun foldSpaces(value: String, trim: Boolean, check: () -> Unit = {}): String = buildString(value.length) {
            var whitespace = false
            value.forEachIndexed { index, ch ->
                if (index % 4096 == 0) check()
                if (space(ch)) { if (!whitespace) append(' '); whitespace = true }
                else { append(ch); whitespace = false }
            }
        }.let { if (trim) it.trim() else it }
        fun clean(value: String): String = foldSpaces(value, true)
        /** Literal lookup keeps punctuation and supports case/diacritic-insensitive references. */
        fun literal(value: String, trim: Boolean = true): String = Normalizer.normalize(foldSpaces(value, trim), Normalizer.Form.NFD)
            .replace(marks, "").lowercase(Locale.ROOT)
        fun literalIndex(value: String, check: () -> Unit): String {
            val result = StringBuilder(value.length)
            var from = 0
            while (from < value.length) {
                check()
                var to = minOf(value.length, from + 8192)
                if (to < value.length && Character.isHighSurrogate(value[to - 1])) to--
                result.append(literal(value.substring(from, to), trim = false))
                from = to
            }
            return foldSpaces(result.toString(), true, check)
        }
    }
}
