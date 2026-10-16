package com.gatotiles.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private data class S(val title: String, val artist: String, val album: String = "")

    private val songs = listOf(
        S("Ojos Color Sol", "Calle 13, Silvio Rodríguez", "Multi_Viral"),
        S("Bajan", "Pescado Rabioso", "Artaud"),
        S("Ruler of Everything", "Tally Hall", "Good & Evil"),
        S("Silvia", "Otra banda"),
        S("Canción del sol", "Rodrigo"),
    )
    private val index = SearchIndex(songs) { listOf(it.title, it.artist, it.album) }

    private fun find(q: String) = index.search(FoldedText.tokens(q)).map { it.title }

    @Test
    fun ignoresAccentsCaseAndWordOrder() {
        assertEquals(listOf("Ojos Color Sol"), find("rodriguez"))
        assertEquals(listOf("Ojos Color Sol"), find("SILVIO calle"))
        assertEquals(listOf("Canción del sol"), find("cancion"))
        assertEquals(emptyList<String>(), find("zzz"))
        assertEquals(songs.size, find("   ").size)
    }

    @Test
    fun titleMatchesComeFirst() {
        // "sol": title word in two songs; "Silvi": title of "Silvia" beats the artist of Ojos Color Sol.
        assertEquals(listOf("Ojos Color Sol", "Canción del sol"), find("sol"))
        assertEquals("Silvia", find("silvi").first())
        assertEquals("Ruler of Everything", find("tally").single())
    }

    @Test
    fun highlightRangesMapBackToTheOriginalText() {
        val t = FoldedText("Silvio Rodríguez")
        assertEquals(listOf(7..15), t.ranges(FoldedText.tokens("rodriguez")))
        assertEquals(listOf(0..1, 7..9), t.ranges(listOf("si", "rod")))
        // Overlapping hits are merged.
        assertEquals(listOf(0..4, 6..10), FoldedText("Bajan Bajan").ranges(listOf("baja", "jan")))
    }
}
