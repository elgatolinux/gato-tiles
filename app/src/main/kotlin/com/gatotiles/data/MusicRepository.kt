package com.gatotiles.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What MediaStore says a file is; only [MUSIC] is used automatically. */
enum class AudioKind { MUSIC, PODCAST, AUDIOBOOK, RECORDING, OTHER }

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val size: Long,
    val mime: String,
    val path: String,
    val folder: String,
    val dateAdded: Long,
    val kind: AudioKind = AudioKind.MUSIC,
) {
    /** Stable identity for caches and scores (survives rescans and MediaStore id changes). */
    val key: String = "%d_%d_%08x".format(size, durationMs / 1000, (title + "|" + artist).hashCode())

    val format: String
        get() = path.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }
            ?: mime.substringAfter('/').uppercase()
}

/** A folder that has audio files, with the automatic decision about it. */
data class FolderInfo(val path: String, val count: Int, val auto: AutoExclusion?)

/** Local music from MediaStore (no network, no streaming). */
class MusicRepository(private val context: Context) {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scannedOnce = MutableStateFlow(false)
    val scannedOnce: StateFlow<Boolean> = _scannedOnce.asStateFlow()
    private var all: List<Song> = emptyList()
    private val _folders = MutableStateFlow<List<FolderInfo>>(emptyList())

    /** Every folder with audio (before the folder rules), sorted by path. */
    val folders: StateFlow<List<FolderInfo>> = _folders.asStateFlow()

    fun byId(id: Long): Song? = _songs.value.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id }

    suspend fun scan(minDurationMs: Long, rules: FolderRules) = withContext(Dispatchers.IO) {
        _scanning.value = true
        try {
            all = query(minDurationMs)
            _folders.value = all.groupBy { it.folder }
                .map { (path, list) ->
                    FolderInfo(path, list.size, FolderClassifier.classify(path, list.map { it.path.substringAfterLast('/') to it.kind }))
                }
                .sortedBy { it.path.lowercase() }
            applyRules(rules)
        } finally {
            _scanning.value = false
            _scannedOnce.value = true
        }
    }

    fun applyRules(rules: FolderRules) {
        val auto = _folders.value.associate { it.path to it.auto?.root }
        _songs.value = all.filter { rules.isIncluded(it.folder, auto[it.folder]) }
    }

    /** How many songs [rules] would use, out of all songs found. */
    fun countIncluded(rules: FolderRules): Int = _folders.value.sumOf { if (rules.isIncluded(it.path, it.auto?.root)) it.count else 0 }

    val totalFound: Int get() = all.size

    private fun query(minDurationMs: Long): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_PODCAST,
        ) + (if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.Audio.Media.IS_AUDIOBOOK) else emptyArray()) +
            (if (Build.VERSION.SDK_INT >= 31) arrayOf(MediaStore.Audio.Media.IS_RECORDING) else emptyArray())
        val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"
        val out = ArrayList<Song>()
        context.contentResolver.query(collection, projection, selection, arrayOf(minDurationMs.toString()), null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            @Suppress("DEPRECATION")
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iMusic = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val iPodcast = c.getColumnIndex(MediaStore.Audio.Media.IS_PODCAST)
            val iBook = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.IS_AUDIOBOOK) else -1
            val iRec = if (Build.VERSION.SDK_INT >= 31) c.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING) else -1
            fun flag(i: Int) = i >= 0 && !c.isNull(i) && c.getInt(i) != 0
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.getString(iData) ?: ""
                val name = c.getString(iName) ?: path.substringAfterLast('/')
                val rawTitle = c.getString(iTitle)
                val title = if (rawTitle.isNullOrBlank()) name.substringBeforeLast('.') else rawTitle
                val artist = c.getString(iArtist).let { if (it.isNullOrBlank() || it == "<unknown>") "Artista desconocido" else it }
                val album = c.getString(iAlbum).let { if (it.isNullOrBlank() || it == "<unknown>") "" else it }
                val folder = if (path.isNotEmpty()) File(path).parent ?: "" else ""
                val kind = when {
                    flag(iRec) -> AudioKind.RECORDING
                    flag(iPodcast) -> AudioKind.PODCAST
                    flag(iBook) -> AudioKind.AUDIOBOOK
                    iMusic >= 0 && !c.isNull(iMusic) && c.getInt(iMusic) == 0 -> AudioKind.OTHER
                    else -> AudioKind.MUSIC
                }
                out.add(
                    Song(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = c.getLong(iAlbumId),
                        durationMs = c.getLong(iDur),
                        size = c.getLong(iSize),
                        mime = c.getString(iMime) ?: "audio/*",
                        path = path,
                        folder = folder,
                        dateAdded = c.getLong(iDate),
                        kind = kind,
                    ),
                )
            }
        }
        return out
    }
}
