package com.gatotiles.util

import java.text.Normalizer

/** Text folded for matching (lowercase, no accents) with a map back to the original indices. */
class FoldedText(val original: String) {
    val folded: String
    /** folded index -> original index */
    private val map: IntArray

    init {
        val sb = StringBuilder(original.length)
        val m = ArrayList<Int>(original.length)
        for ((i, ch) in original.withIndex()) {
            val f = foldChar(ch)
            for (c in f) {
                sb.append(c)
                m.add(i)
            }
        }
        folded = sb.toString()
        map = m.toIntArray()
    }

    /** Original-text index ranges (inclusive) of every occurrence of the folded [tokens], merged. */
    fun ranges(tokens: List<String>): List<IntRange> {
        if (tokens.isEmpty() || folded.isEmpty()) return emptyList()
        val hits = ArrayList<IntRange>()
        for (t in tokens) {
            var from = 0
            while (true) {
                val at = folded.indexOf(t, from)
                if (at < 0) break
                hits.add(map[at]..map[at + t.length - 1])
                from = at + t.length
            }
        }
        if (hits.size < 2) return hits
        hits.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var cur = hits[0]
        for (k in 1 until hits.size) {
            val r = hits[k]
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else {
                merged.add(cur)
                r
            }
        }
        merged.add(cur)
        return merged
    }

    companion object {
        private val marks = Regex("\\p{M}+")
        private val cache = HashMap<Char, String>()

        private fun foldChar(ch: Char): String {
            if (ch.code < 128) return ch.lowercaseChar().toString()
            return synchronized(cache) {
                cache.getOrPut(ch) { Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).replace(marks, "").lowercase() }
            }
        }

        fun fold(s: String): String = buildString(s.length) { for (ch in s) append(foldChar(ch)) }

        /** Search words of a query, folded; empty when the query is blank. */
        fun tokens(query: String): List<String> = fold(query).split(' ', '\t', ',').filter { it.isNotBlank() }.distinct()
    }
}

/**
 * Library search: every word of the query must appear in the title, artist, album or folder
 * name (in any order, accents ignored). Results are ranked: title matches first, then
 * artist, album and folder; a match at the start of a word beats one inside a word.
 */
class SearchIndex<T>(items: List<T>, fields: (T) -> List<String>) {
    private class Entry<T>(val item: T, val fields: List<String>)

    private val entries = items.map { Entry(it, fields(it).map(FoldedText::fold)) }

    fun search(tokens: List<String>): List<T> {
        if (tokens.isEmpty()) return entries.map { it.item }
        val scored = ArrayList<Pair<T, Int>>()
        for (e in entries) {
            var total = 0
            var ok = true
            for (t in tokens) {
                var best = -1
                for ((fi, f) in e.fields.withIndex()) {
                    val at = f.indexOf(t)
                    if (at < 0) continue
                    val wordStart = at == 0 || !f[at - 1].isLetterOrDigit()
                    // Earlier fields (title) and word starts score higher.
                    val s = (4 - fi.coerceAtMost(3)) * 10 + (if (wordStart) 5 else 0) + (if (at == 0) 3 else 0)
                    if (s > best) best = s
                }
                if (best < 0) {
                    ok = false
                    break
                }
                total += best
            }
            if (ok) scored.add(e.item to total)
        }
        // Stable sort: equal scores keep the library order.
        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
