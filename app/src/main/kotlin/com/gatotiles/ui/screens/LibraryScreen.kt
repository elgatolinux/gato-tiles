package com.gatotiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gatotiles.data.Albums
import com.gatotiles.ui.components.AddToAlbumSheet
import com.gatotiles.ui.components.AlbumCard
import com.gatotiles.ui.components.AlbumNameDialog
import com.gatotiles.ui.components.NewAlbumCard
import com.gatotiles.ui.components.albumSongs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.data.SortOrder
import com.gatotiles.data.Song
import com.gatotiles.ui.components.AlbumArt
import com.gatotiles.ui.components.formatDuration
import com.gatotiles.util.FoldedText
import com.gatotiles.util.SearchIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(onOpenSong: (Song) -> Unit, onOpenSettings: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val app = GatoApp.app
    val songs by app.music.songs.collectAsStateWithLifecycle()
    val scanning by app.music.scanning.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val lastPlayed by app.scores.lastPlayed.collectAsStateWithLifecycle()
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_SONGS) }
    var albumSheetFor by remember { mutableStateOf<Song?>(null) }
    var creatingAlbum by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val best by app.scores.best.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyReady by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val searching = searchFocused || query.isNotEmpty()

    LaunchedEffect(Unit) { app.preview.stop() }

    val starsBySong = remember(best) {
        val m = HashMap<String, Int>()
        for ((k, v) in best) {
            val songKey = k.substringBefore('|')
            m[songKey] = maxOf(m[songKey] ?: 0, v.stars)
        }
        m
    }
    val sorted = remember(songs, settings.sortOrder, lastPlayed) {
        when (settings.sortOrder) {
            SortOrder.TITLE -> songs.sortedBy { FoldedText.fold(it.title) }
            SortOrder.ARTIST -> songs.sortedWith(compareBy<Song> { FoldedText.fold(it.artist) }.thenBy { FoldedText.fold(it.album) }.thenBy { FoldedText.fold(it.title) })
            SortOrder.ALBUM -> songs.sortedWith(compareBy<Song> { FoldedText.fold(it.album) }.thenBy { FoldedText.fold(it.title) })
            SortOrder.DATE_ADDED -> songs.sortedByDescending { it.dateAdded }
            SortOrder.RECENT -> songs.sortedByDescending { lastPlayed[it.key] ?: 0L }
            SortOrder.DURATION -> songs.sortedByDescending { it.durationMs }
        }
    }
    val index = remember(sorted) { SearchIndex(sorted) { listOf(it.title, it.artist, it.album, it.folder.substringAfterLast('/')) } }
    val tokens = remember(query) { FoldedText.tokens(query) }
    val visible = remember(index, tokens, onlyReady, scanning) {
        val list = index.search(tokens)
        if (onlyReady) list.filter { app.charts.isCached(it) } else list
    }
    val readyCount = remember(songs, scanning, best) { songs.count { app.charts.isCached(it) } }
    val continueSong = remember(songs, lastPlayed) {
        val key = lastPlayed.maxByOrNull { it.value }?.key
        songs.firstOrNull { it.key == key }
    }
    val cs = MaterialTheme.colorScheme

    // Collapsing title: hides while scrolling down the list, comes back at the top.
    val header = remember { HeaderState() }

    fun closeSearch() {
        query = ""
        focus.clearFocus()
        keyboard?.hide()
        header.offset = 0f
    }
    BackHandler(enabled = searching) { closeSearch() }
    // New results always start at the top.
    LaunchedEffect(tokens, onlyReady) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
        if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0)
    }
    val byKey = remember(songs) { songs.associateBy { it.key } }
    val visibleAlbums = remember(albums, tokens) {
        if (tokens.isEmpty()) albums else albums.filter { a -> tokens.all { FoldedText.fold(a.name).contains(it) } }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide()
                    focus.clearFocus()
                }
                val dy = available.y
                if (dy >= 0f) return Offset.Zero
                val new = (header.offset + dy).coerceAtLeast(-header.full)
                val used = new - header.offset
                header.offset = new
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy <= 0f) return Offset.Zero
                val new = (header.offset + dy).coerceAtMost(0f)
                val used = new - header.offset
                header.offset = new
                return Offset(0f, used)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to cs.primaryContainer.copy(alpha = 0.45f), 0.35f to cs.background, 1f to cs.background)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().nestedScroll(connection)) {
            AnimatedVisibility(visible = !searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(
                    Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            header.full = p.height.toFloat()
                            val off = header.offset.coerceIn(-p.height.toFloat(), 0f).roundToInt()
                            layout(p.width, p.height + off) { p.placeRelative(0, off) }
                        }
                        .graphicsLayer { alpha = if (header.full > 0f) (1f + header.offset / header.full).coerceIn(0f, 1f) else 1f },
                ) {
                    LibraryTitle(
                        subtitle = if (scanning) {
                            "Buscando canciones…"
                        } else {
                            (if (songs.size == 1) "1 canción" else "${songs.size} canciones") + " · " +
                                (if (readyCount == 1) "1 pista lista" else "$readyCount pistas listas")
                        },
                        onRefresh = {
                            scope.launch {
                                app.music.scan(settings.minDurationSec * 1000L, settings.folderRules)
                                app.charts.refreshCachedKeys(app.music.songs.value)
                            }
                        },
                        onSettings = onOpenSettings,
                    )
                }
            }
            SearchField(
                query = query,
                onQuery = { query = it },
                active = searching,
                interaction = searchInteraction,
                onClose = ::closeSearch,
                onSubmit = { keyboard?.hide() },
                placeholder = if (tab == TAB_SONGS) "Buscar canción, artista o álbum" else "Buscar en mis álbumes",
            )
            LibraryTabs(tab, songs.size, albums.size) { tab = it }
            if (tab == TAB_SONGS) Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { sortMenu = true },
                        label = { Text(settings.sortOrder.label) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortOrder.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.label) },
                                onClick = {
                                    sortMenu = false
                                    app.settings.update { it.copy(sortOrder = o) }
                                },
                                trailingIcon = { if (o == settings.sortOrder) Icon(Icons.Rounded.CheckCircle, null) },
                            )
                        }
                    }
                }
                FilterChip(
                    selected = onlyReady,
                    onClick = { onlyReady = !onlyReady },
                    label = { Text("Pistas listas") },
                    leadingIcon = if (onlyReady) {
                        { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(tokens.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        if (visible.size == 1) "1 resultado" else "${visible.size} resultados",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (tab == TAB_ALBUMS) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(152.dp),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                ) {
                    gridItems(visibleAlbums, key = { it.id }) { album ->
                        AlbumCard(album, albumSongs(album, byKey), onClick = {
                            keyboard?.hide()
                            onOpenAlbum(album.id)
                        }, modifier = Modifier.animateItem())
                    }
                    if (tokens.isEmpty()) item(key = "#new") { NewAlbumCard(onClick = { creatingAlbum = true }) }
                }
            } else Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = if (continueSong != null && !searching) 120.dp else 24.dp),
                ) {
                    if (songs.isEmpty()) {
                        item(key = "empty") { EmptyLibrary(scanning) }
                    } else if (visible.isEmpty()) {
                        item(key = "no-results") { NoResults(query, onlyReady, onClear = ::closeSearch, onShowAll = { onlyReady = false }) }
                    }
                    items(visible, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            tokens = tokens,
                            ready = app.charts.isCached(song),
                            stars = starsBySong[song.key] ?: 0,
                            onClick = {
                                keyboard?.hide()
                                onOpenSong(song)
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                keyboard?.hide()
                                albumSheetFor = song
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = continueSong != null && !searching && tab == TAB_SONGS,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            continueSong?.let { ContinueCard(it) { onOpenSong(it) } }
        }
    }
    albumSheetFor?.let { AddToAlbumSheet(it, onDismiss = { albumSheetFor = null }) }
    if (creatingAlbum) {
        AlbumNameDialog("Nuevo álbum", Albums.freeName(albums), "Crear", onDismiss = { creatingAlbum = false }) { name ->
            creatingAlbum = false
            onOpenAlbum(app.albums.create(name))
        }
    }
}

private const val TAB_SONGS = 0
private const val TAB_ALBUMS = 1

/** "Canciones / Álbumes" switch under the search bar. */
@Composable
private fun LibraryTabs(tab: Int, songs: Int, albums: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(44.dp),
    ) {
        Row(Modifier.padding(4.dp)) {
            for ((i, label) in listOf("Canciones" to songs, "Álbumes" to albums).withIndex()) {
                val selected = i == tab
                val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, label = "tabBg")
                val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, label = "tabFg")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (i == TAB_SONGS) Icons.Rounded.MusicNote else Icons.Rounded.LibraryMusic, null, Modifier.size(18.dp), tint = fg)
                    Spacer(Modifier.width(6.dp))
                    Text(label.first, style = MaterialTheme.typography.labelLarge, color = fg)
                    Spacer(Modifier.width(6.dp))
                    Text("${label.second}", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** Offsets of the collapsing title, read during layout (no recomposition while scrolling). */
private class HeaderState {
    var full by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)
}

@Composable
private fun LibraryTitle(subtitle: String, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Gato Tiles", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Volver a buscar") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Ajustes") }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    active: Boolean,
    interaction: MutableInteractionSource,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
) {
    val cs = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val container by animateColorAsState(if (active) cs.surfaceContainerHighest else cs.surfaceContainerHigh, label = "searchBg")
    val border by animateColorAsState(if (active) cs.primary.copy(alpha = 0.7f) else Color.Transparent, label = "searchBorder")
    val sidePad by animateDpAsState(if (active) 10.dp else 16.dp, label = "searchPad")
    Surface(
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.5.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 6.dp)
            .height(52.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(active, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "searchIcon") { on ->
                if (on) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cerrar búsqueda") }
                } else {
                    IconButton(onClick = { focusRequester.requestFocus() }) { Icon(Icons.Rounded.Search, "Buscar", tint = cs.onSurfaceVariant) }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    interactionSource = interaction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // With the keyboard already hidden, Back closes the search (the field
                        // would otherwise only drop its focus).
                        .onPreviewKeyEvent {
                            if (it.key != Key.Back) return@onPreviewKeyEvent false
                            if (it.type == KeyEventType.KeyUp) onClose()
                            true
                        },
                )
            }
            AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Borrar texto") }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(scanning: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (scanning) {
            CircularProgressIndicator()
            Text("Buscando música en tu teléfono…", color = cs.onSurfaceVariant)
        } else {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(64.dp), tint = cs.primary)
            Text("No encontré canciones", style = MaterialTheme.typography.titleMedium)
            Text(
                "Copia archivos MP3, FLAC, OGG, M4A u OPUS a tu teléfono, revisa Ajustes → Carpetas y toca actualizar.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NoResults(query: String, onlyReady: Boolean, onClear: () -> Unit, onShowAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.SearchOff, null, Modifier.size(56.dp), tint = cs.onSurfaceVariant)
        Text(
            if (query.isBlank()) "Todavía no hay pistas listas" else "Nada para «${query.trim()}»",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (onlyReady) "Solo estás viendo canciones con la pista ya generada." else "Prueba con otra palabra: busco en títulos, artistas, álbumes y carpetas.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onlyReady) TextButton(onClick = onShowAll) { Text("Ver todas") }
            if (query.isNotBlank()) TextButton(onClick = onClear) { Text("Borrar búsqueda") }
        }
    }
}

/** [text] with the parts that match the search [tokens] highlighted. */
@Composable
private fun highlighted(text: String, tokens: List<String>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, tokens, color) {
        val ranges = if (tokens.isEmpty()) emptyList() else FoldedText(text).ranges(tokens)
        if (ranges.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                for (r in ranges) addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), r.first, r.last + 1)
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    tokens: List<String>,
    ready: Boolean,
    stars: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song, Modifier.size(56.dp), corner = 14.dp, sizePx = 160)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlighted(song.title, tokens),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildString {
                append(song.artist)
                if (tokens.isNotEmpty() && song.album.isNotEmpty()) append(" • ").append(song.album)
                append(" • ").append(formatDuration(song.durationMs))
            }
            Text(
                highlighted(details, tokens),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (stars > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = Color(0xFFFFD66E))
                    Text("$stars", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) {
                    Icon(Icons.Rounded.CheckCircle, "Pista lista", Modifier.size(14.dp), tint = cs.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(song.format, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun ContinueCard(song: Song, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(26.dp),
            color = cs.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(song, Modifier.size(52.dp), corner = 16.dp, sizePx = 160)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Seguir jugando", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape)) {
                    Icon(Icons.Rounded.PlayArrow, "Jugar")
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
