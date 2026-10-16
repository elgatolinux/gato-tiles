package com.gatotiles.data

import android.content.Context
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.game.GameResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A list of songs made by the player ("Favoritas" is one that always exists). */
@Serializable
data class Album(
    val id: String,
    val name: String,
    /** Song keys ([Song.key]) in play order. */
    val songs: List<String> = emptyList(),
    val created: Long = 0,
) {
    val isFavorites: Boolean get() = id == Albums.FAVORITES_ID
}

/** Pure album list operations (easy to test); [AlbumRepository] saves the result. */
object Albums {
    const val FAVORITES_ID = "favoritas"
    const val FAVORITES_NAME = "Favoritas"

    /** Favorites first, always present. */
    fun normalize(list: List<Album>): List<Album> {
        val fav = list.firstOrNull { it.isFavorites } ?: Album(FAVORITES_ID, FAVORITES_NAME)
        return listOf(fav.copy(name = FAVORITES_NAME, songs = fav.songs.distinct())) +
            list.filter { !it.isFavorites }.map { it.copy(songs = it.songs.distinct()) }
    }

    fun update(list: List<Album>, id: String, f: (Album) -> Album): List<Album> = list.map { if (it.id == id) f(it) else it }

    fun add(list: List<Album>, id: String, keys: List<String>) = update(list, id) { a -> a.copy(songs = a.songs + keys.filter { it !in a.songs }.distinct()) }

    fun remove(list: List<Album>, id: String, key: String) = update(list, id) { a -> a.copy(songs = a.songs - key) }

    fun toggle(list: List<Album>, id: String, key: String): List<Album> {
        val a = list.firstOrNull { it.id == id } ?: return list
        return if (key in a.songs) remove(list, id, key) else add(list, id, listOf(key))
    }

    fun move(list: List<Album>, id: String, from: Int, to: Int) = update(list, id) { a ->
        if (from !in a.songs.indices || to !in a.songs.indices || from == to) {
            a
        } else {
            val m = a.songs.toMutableList()
            m.add(to, m.removeAt(from))
            a.copy(songs = m)
        }
    }

    /** A name not used yet: "Mi álbum", "Mi álbum 2"... */
    fun freeName(list: List<Album>, base: String = "Mi álbum"): String {
        val names = list.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        var i = 2
        while ("$base $i".lowercase() in names) i++
        return "$base $i"
    }
}

@Serializable
private data class AlbumFile(val albums: List<Album> = emptyList())

class AlbumRepository(context: Context) {
    private val file = File(context.filesDir, "albums.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _albums = MutableStateFlow(Albums.normalize(load()))
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    fun byId(id: String): Album? = _albums.value.firstOrNull { it.id == id }

    fun isFavorite(songKey: String): Boolean = byId(Albums.FAVORITES_ID)?.songs?.contains(songKey) == true

    fun toggleFavorite(songKey: String) = edit { Albums.toggle(it, Albums.FAVORITES_ID, songKey) }

    fun toggle(id: String, songKey: String) = edit { Albums.toggle(it, id, songKey) }

    fun add(id: String, keys: List<String>) = edit { Albums.add(it, id, keys) }

    fun remove(id: String, songKey: String) = edit { Albums.remove(it, id, songKey) }

    fun move(id: String, from: Int, to: Int) = edit { Albums.move(it, id, from, to) }

    fun rename(id: String, name: String) = edit { list -> Albums.update(list, id) { if (it.isFavorites) it else it.copy(name = name.trim().ifEmpty { it.name }) } }

    fun delete(id: String) = edit { list -> list.filter { it.id != id || it.isFavorites } }

    /** Creates an album and returns its id. */
    fun create(name: String, songs: List<String> = emptyList()): String {
        val id = "a" + System.currentTimeMillis().toString(36) + (0..999).random()
        edit { it + Album(id, name.trim().ifEmpty { Albums.freeName(it) }, songs.distinct(), System.currentTimeMillis()) }
        return id
    }

    @Synchronized
    private fun edit(f: (List<Album>) -> List<Album>) {
        val next = Albums.normalize(f(_albums.value))
        if (next == _albums.value) return
        _albums.value = next
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(AlbumFile.serializer(), AlbumFile(next)))
            tmp.renameTo(file)
        }
    }

    private fun load(): List<Album> = runCatching {
        if (file.exists()) json.decodeFromString(AlbumFile.serializer(), file.readText()).albums else emptyList()
    }.getOrDefault(emptyList())
}

/**
 * Playing an album song after song. [results] keeps the last result of each song (a retry
 * replaces it).
 */
class AlbumRun(
    val albumId: String,
    val name: String,
    val songs: List<Song>,
    val difficulty: Difficulty,
    val mode: GameMode,
    val autoNext: Boolean,
) {
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _results = MutableStateFlow<Map<Int, GameResult>>(emptyMap())
    val results: StateFlow<Map<Int, GameResult>> = _results.asStateFlow()
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    val current: Song get() = songs[_index.value]
    val next: Song? get() = songs.getOrNull(_index.value + 1)

    fun record(result: GameResult) {
        _results.value = _results.value + (_index.value to result)
    }

    fun advance() {
        if (_index.value + 1 < songs.size) _index.value++ else _finished.value = true
    }

    fun finish() {
        _finished.value = true
    }
}
