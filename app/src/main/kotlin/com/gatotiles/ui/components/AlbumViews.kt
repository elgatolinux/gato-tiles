package com.gatotiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.ui.theme.FollowAppTheme
import com.gatotiles.data.Album
import com.gatotiles.data.Albums
import com.gatotiles.data.Song

/** Cover of an album: the art of its first songs in a 2×2 mosaic (or one, or an icon). */
@Composable
fun AlbumCover(album: Album, songs: List<Song>, modifier: Modifier = Modifier, corner: Dp = 20.dp, sizePx: Int = 256) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(corner))) {
        when {
            songs.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (album.isFavorites) {
                            Brush.linearGradient(listOf(Color(0xFFFF5C8A), Color(0xFF8A5CFF)))
                        } else {
                            Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (album.isFavorites) Icons.Rounded.Favorite else Icons.Rounded.LibraryMusic,
                    null,
                    tint = if (album.isFavorites) Color.White else cs.onPrimaryContainer,
                    modifier = Modifier.fillMaxSize(0.38f),
                )
            }
            songs.size < 4 -> AlbumArt(songs.first(), Modifier.fillMaxSize(), corner = 0.dp, sizePx = sizePx)
            else -> Column(Modifier.fillMaxSize()) {
                for (r in 0 until 2) {
                    Row(Modifier.weight(1f)) {
                        for (c in 0 until 2) {
                            AlbumArt(songs[r * 2 + c], Modifier.weight(1f).fillMaxSize(), corner = 0.dp, sizePx = sizePx / 2)
                        }
                    }
                }
            }
        }
        if (album.isFavorites && songs.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF5C8A)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
        }
    }
}

/** Songs of [album] that are in the library right now, in album order. */
fun albumSongs(album: Album, byKey: Map<String, Song>): List<Song> = album.songs.mapNotNull { byKey[it] }

/** Pick which albums [song] belongs to; can create a new album too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(song: Song, onDismiss: () -> Unit) {
    val app = GatoApp.app
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val songs by app.music.songs.collectAsStateWithLifecycle()
    val byKey = remember(songs) { songs.associateBy { it.key } }
    var creating by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    FollowAppTheme {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(song, Modifier.size(44.dp), corner = 12.dp, sizePx = 128)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Guardar en un álbum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(song.title, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                for (a in albums) {
                    val inside = song.key in a.songs
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { app.albums.toggle(a.id, song.key) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumCover(a, albumSongs(a, byKey), Modifier.size(48.dp), corner = 12.dp, sizePx = 128)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songCount(a.songs.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Icon(
                            if (inside) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            if (inside) "Quitar" else "Añadir",
                            tint = if (inside) cs.primary else cs.outline,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, null, tint = cs.primary) }
                    Spacer(Modifier.width(14.dp))
                    Text("Nuevo álbum", style = MaterialTheme.typography.titleSmall, color = cs.primary)
                }
            }
        }
    }
    if (creating) {
        AlbumNameDialog(
            title = "Nuevo álbum",
            initial = Albums.freeName(albums),
            confirm = "Crear",
            onDismiss = { creating = false },
        ) { name ->
            app.albums.create(name, listOf(song.key))
            creating = false
        }
    }
}

@Composable
fun AlbumNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.copy(text = it.text.take(40)) },
                singleLine = true,
                label = { Text("Nombre") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.text) }, enabled = text.text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

fun songCount(n: Int): String = if (n == 1) "1 canción" else "$n canciones"

/** Grid card of an album in the library. */
@Composable
fun AlbumCard(album: Album, songs: List<Song>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        AlbumCover(album, songs, Modifier.fillMaxWidth().aspectRatio(1f), corner = 20.dp)
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            songCount(songs.size) + if (songs.isNotEmpty()) " · " + formatDuration(songs.sumOf { it.durationMs }) else "",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Card that creates a new album. */
@Composable
fun NewAlbumCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Nuevo álbum", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.primary)
        Text("Junta tus canciones", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}
