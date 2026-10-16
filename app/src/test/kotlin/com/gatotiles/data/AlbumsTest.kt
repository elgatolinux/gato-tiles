package com.gatotiles.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsTest {
    @Test
    fun favoritesAlwaysFirst() {
        val list = Albums.normalize(listOf(Album("x", "Rock", listOf("a", "a", "b"))))
        assertEquals(Albums.FAVORITES_ID, list[0].id)
        assertEquals(listOf("a", "b"), list[1].songs)
        assertEquals(2, Albums.normalize(list).size)
    }

    @Test
    fun addRemoveToggleMove() {
        var list = Albums.normalize(listOf(Album("x", "Rock")))
        list = Albums.add(list, "x", listOf("a", "b", "a", "c"))
        assertEquals(listOf("a", "b", "c"), list[1].songs)
        list = Albums.add(list, "x", listOf("b", "d"))
        assertEquals(listOf("a", "b", "c", "d"), list[1].songs)
        list = Albums.move(list, "x", 3, 0)
        assertEquals(listOf("d", "a", "b", "c"), list[1].songs)
        list = Albums.move(list, "x", 0, 9)
        assertEquals(listOf("d", "a", "b", "c"), list[1].songs)
        list = Albums.remove(list, "x", "a")
        assertEquals(listOf("d", "b", "c"), list[1].songs)
        list = Albums.toggle(list, Albums.FAVORITES_ID, "d")
        assertEquals(listOf("d"), list[0].songs)
        list = Albums.toggle(list, Albums.FAVORITES_ID, "d")
        assertTrue(list[0].songs.isEmpty())
    }

    @Test
    fun freeNames() {
        val list = Albums.normalize(listOf(Album("x", "Mi álbum"), Album("y", "mi álbum 2")))
        assertEquals("Mi álbum 3", Albums.freeName(list))
        assertEquals("Viaje", Albums.freeName(list, "Viaje"))
    }
}
