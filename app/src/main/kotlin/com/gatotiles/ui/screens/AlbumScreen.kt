package com.gatotiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.ui.theme.FollowAppTheme
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.data.Album
import com.gatotiles.data.AlbumRun
import com.gatotiles.data.GameMode
import com.gatotiles.data.Song
import com.gatotiles.ui.components.AlbumArt
import com.gatotiles.ui.components.AlbumCover
import com.gatotiles.ui.components.AlbumNameDialog
import com.gatotiles.ui.components.albumSongs
import com.gatotiles.ui.components.formatDuration
import com.gatotiles.ui.components.songCount
import com.gatotiles.util.FoldedText
import com.gatotiles.util.SearchIndex

@Composable
fun AlbumScreen(albumId: String, onBack: () -> Unit, onOpenSong: (Song) -> Unit, onAddSongs: () -> Unit, onStartRun: () -> Unit) {
    val app = GatoApp.app
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val library by app.music.songs.collectAsStateWithLifecycle()
    val best by app.scores.best.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    if (album == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val byKey = remember(library) { library.associateBy { it.key } }
    val songs = albumSongs(album, byKey)
    val missing = album.songs.size - songs.size
    val stars = remember(best) {
        val m = HashMap<String, Int>()
        for ((k, v) in best) {
            val key = k.substringBefore('|')
            m[key] = maxOf(m[key] ?: 0, v.stars)
        }
        m
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var playSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val cs = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to cs.primaryContainer.copy(alpha = 0.5f), 0.4f to cs.background, 1f to cs.background)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "#top") {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Más") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Añadir canciones") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                onClick = {
                                    menu = false
                                    onAddSongs()
                                },
                            )
                            if (!album.isFavorites) {
                                DropdownMenuItem(
                                    text = { Text("Cambiar nombre") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = {
                                        menu = false
                                        renaming = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Eliminar álbum") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                    onClick = {
                                        menu = false
                                        deleting = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "#header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AlbumCover(album, songs, Modifier.size(196.dp), corner = 32.dp, sizePx = 400)
                    Spacer(Modifier.height(18.dp))
                    Text(album.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        songCount(songs.size) + (if (songs.isNotEmpty()) " · " + formatDuration(songs.sumOf { it.durationMs }) else "") +
                            if (missing > 0) " · $missing no disponibles" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { playSheet = true },
                            enabled = songs.isNotEmpty(),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.height(54.dp).weight(1.2f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Jugar álbum", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(onClick = onAddSongs, shape = RoundedCornerShape(28.dp), modifier = Modifier.height(54.dp).weight(1f)) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Añadir")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (songs.isEmpty()) {
                item(key = "#empty") {
                    Text(
                        if (album.isFavorites) {
                            "Toca el corazón en una canción para tenerla aquí."
                        } else {
                            "Este álbum está vacío. Añade canciones con el botón de arriba, o desde una canción (menú ⋮ → Guardar en un álbum)."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            } else {
                item(key = "#hint") {
                    Text(
                        "Arrastra ≡ para cambiar el orden en que se juegan",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                    )
                }
            }
            items(songs, key = { it.key }) { song ->
                val dragging = dragKey == song.key
                Row(
                    Modifier
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                        .graphicsLayer {
                            if (dragging) {
                                translationY = dragOffset
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .fillMaxWidth()
                        .background(if (dragging) cs.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { onOpenSong(song) }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${album.songs.indexOf(song.key) + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    AlbumArt(song, Modifier.size(50.dp), corner = 12.dp, sizePx = 160)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            song.artist + " · " + formatDuration(song.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val st = stars[song.key] ?: 0
                    if (st > 0) {
                        Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = Color(0xFFFFD66E))
                        Text("$st", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { app.albums.remove(album.id, song.key) }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, "Quitar del álbum", tint = cs.onSurfaceVariant)
                    }
                    Icon(
                        Icons.Rounded.DragHandle,
                        "Mover",
                        tint = if (dragging) cs.primary else cs.onSurfaceVariant,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp)
                            .pointerInput(song.key) {
                                detectDragGestures(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragKey = song.key
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        dragKey = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        dragKey = null
                                        dragOffset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val me = visible.firstOrNull { it.key == song.key } ?: return@detectDragGestures
                                    val center = me.offset + dragOffset + me.size / 2
                                    val target = visible.firstOrNull {
                                        it.key != song.key && it.key is String && (it.key as String) in album.songs &&
                                            center > it.offset && center < it.offset + it.size
                                    } ?: return@detectDragGestures
                                    val list = app.albums.byId(album.id)?.songs ?: return@detectDragGestures
                                    val from = list.indexOf(song.key)
                                    val to = list.indexOf(target.key as String)
                                    if (from < 0 || to < 0) return@detectDragGestures
                                    app.albums.move(album.id, from, to)
                                    dragOffset += me.offset - target.offset
                                }
                            },
                    )
                }
            }
        }
    }
    if (renaming) {
        AlbumNameDialog("Cambiar nombre", album.name, "Guardar", onDismiss = { renaming = false }) {
            app.albums.rename(album.id, it)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("¿Eliminar «${album.name}»?") },
            text = { Text("Solo se borra el álbum: tus canciones y récords siguen ahí.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    app.albums.delete(album.id)
                    onBack()
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
    if (playSheet) {
        PlayAlbumSheet(album, songs, onDismiss = { playSheet = false }) {
            playSheet = false
            onStartRun()
        }
    }
}

/** Mode, difficulty and order for playing the whole album. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayAlbumSheet(album: Album, songs: List<Song>, onDismiss: () -> Unit, onStart: () -> Unit) {
    val app = GatoApp.app
    val s by app.settings.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(s.lastMode) }
    var difficulty by remember { mutableStateOf(s.lastDifficulty) }
    var shuffle by remember { mutableStateOf(s.albumShuffle) }
    var autoNext by remember { mutableStateOf(s.albumAutoNext) }
    val ready = songs.count { app.charts.isCached(it) }
    val cs = MaterialTheme.colorScheme
    FollowAppTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Jugar «${album.name}»", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    songCount(songs.size) + " · " + formatDuration(songs.sumOf { it.durationMs }) + " · ${s.lanes} carriles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Label("Modo")
                Segmented(GameMode.entries.map { it.label }, mode.ordinal) { mode = GameMode.entries[it] }
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Label("Dificultad")
                Segmented(Difficulty.entries.map { it.label }, difficulty.ordinal) { difficulty = Difficulty.entries[it] }
                Label("Orden")
                Segmented(listOf("En orden", "Aleatorio"), if (shuffle) 1 else 0) { shuffle = it == 1 }
                Surface(onClick = { autoNext = !autoNext }, shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Seguir sola a la siguiente", style = MaterialTheme.typography.titleSmall)
                            Text("Al terminar una canción, la siguiente empieza tras una cuenta atrás", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = autoNext, onCheckedChange = { autoNext = it })
                    }
                }
                if (ready < songs.size) {
                    Text(
                        "${songs.size - ready} ${if (songs.size - ready == 1) "canción todavía no tiene" else "canciones todavía no tienen"} pista: se crean mientras juegas la anterior.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        app.settings.update { it.copy(lastMode = mode, lastDifficulty = difficulty, albumShuffle = shuffle, albumAutoNext = autoNext) }
                        app.run = AlbumRun(album.id, album.name, if (shuffle) songs.shuffled() else songs, difficulty, mode, autoNext)
                        onStart()
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Empezar", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Library list with check marks: what is checked is in the album (changes save at once). */
@Composable
fun AlbumAddSongsScreen(albumId: String, onBack: () -> Unit) {
    val app = GatoApp.app
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val library by app.music.songs.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    if (album == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var query by remember { mutableStateOf("") }
    val sorted = remember(library) { library.sortedBy { FoldedText.fold(it.title) } }
    val index = remember(sorted) { SearchIndex(sorted) { listOf(it.title, it.artist, it.album, it.folder.substringAfterLast('/')) } }
    val visible = remember(index, query) { index.search(FoldedText.tokens(query)) }
    val inAlbum = album.songs.toSet()
    val cs = MaterialTheme.colorScheme
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
            Column(Modifier.weight(1f)) {
                Text("Añadir canciones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${album.name} · ${songCount(album.songs.size)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            TextButton(onClick = onBack) { Text("Listo") }
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = cs.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(48.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar canción, artista o álbum", color = cs.onSurfaceVariant, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Borrar texto") }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visible, key = { it.id }) { song ->
                val checked = song.key in inAlbum
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { app.albums.toggle(album.id, song.key) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(song, Modifier.size(48.dp), corner = 12.dp, sizePx = 160)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (checked) "En el álbum" else "Añadir",
                        tint = if (checked) cs.primary else cs.outline,
                    )
                }
            }
        }
    }
}
