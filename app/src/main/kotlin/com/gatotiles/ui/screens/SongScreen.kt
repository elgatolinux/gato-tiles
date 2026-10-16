package com.gatotiles.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.data.AnalysisState
import com.gatotiles.data.GameMode
import com.gatotiles.data.ScoreRepository
import com.gatotiles.data.Song
import com.gatotiles.ui.ThemeSeed
import com.gatotiles.ui.components.AddToAlbumSheet
import com.gatotiles.ui.components.AlbumArt
import com.gatotiles.ui.components.InfoPill
import com.gatotiles.ui.components.SongColorsSheet
import com.gatotiles.ui.components.Waveform
import com.gatotiles.ui.components.formatDuration
import com.gatotiles.ui.components.formatScore
import com.gatotiles.util.Blur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SongScreen(songId: Long, onBack: () -> Unit, onPlay: (Difficulty, GameMode) -> Unit) {
    val app = GatoApp.app
    val song = remember(songId) { app.music.byId(songId) }
    if (song == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val analysis by app.charts.state(song).collectAsStateWithLifecycle()
    val bestMap by app.scores.best.collectAsStateWithLifecycle()
    var difficulty by rememberSaveable { mutableStateOf(settings.lastDifficulty) }
    var mode by rememberSaveable { mutableStateOf(settings.lastMode) }
    var menu by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var albumSheet by remember { mutableStateOf(false) }
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val favorite = albums.firstOrNull { it.isFavorites }?.songs?.contains(song.key) == true
    val chart = (analysis as? AnalysisState.Ready)?.chart

    LaunchedEffect(song.id) {
        app.charts.request(song)
        ThemeSeed.seed.value = app.colorsFor(song).main.seed
    }
    LaunchedEffect(song.id, chart != null, settings.autoPreview) {
        if (settings.autoPreview) app.preview.play(song, chart?.previewMs ?: (song.durationMs * 0.3).toInt())
    }
    DisposableEffect(song.id) { onDispose { app.preview.stop() } }

    val backdrop by produceState<ImageBitmap?>(null, song.id) {
        val art = app.art.load(song, 256)
        value = if (art == null) null else withContext(Dispatchers.Default) { Blur.backdrop(art, 9, 16, 54, 4).asImageBitmap() }
    }
    val cs = MaterialTheme.colorScheme
    val lanes = settings.lanes
    val best = bestMap[ScoreRepository.key(song.key, difficulty, mode, lanes)]

    Box(Modifier.fillMaxSize()) {
        backdrop?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.85f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to cs.background.copy(alpha = 0.55f),
                        0.45f to cs.background.copy(alpha = 0.82f),
                        1f to cs.background,
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
                Text(
                    song.album.ifEmpty { "Canción" },
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { app.albums.toggleFavorite(song.key) }) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (favorite) "Quitar de favoritas" else "Añadir a favoritas",
                        tint = if (favorite) Color(0xFFFF5C8A) else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { paletteOpen = true }) { Icon(Icons.Rounded.Palette, "Paleta de colores") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Guardar en un álbum") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            onClick = {
                                menu = false
                                albumSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Paleta de colores") },
                            leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                            onClick = {
                                menu = false
                                paletteOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Volver a generar la pista") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = {
                                menu = false
                                app.charts.delete(song)
                                app.charts.request(song)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Borrar pista generada") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = {
                                menu = false
                                app.charts.delete(song)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            AlbumArt(
                song,
                Modifier
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .shadow(28.dp, RoundedCornerShape(30.dp), ambientColor = cs.primary, spotColor = cs.primary),
                corner = 30.dp,
                sizePx = 720,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp).basicMarquee(),
            )
            Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
            Waveform(
                values = chart?.waveform,
                modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            SongFacts(song, chart?.bpm, chart?.chart(difficulty, lanes)?.notes?.size, lanes) {
                app.settings.update { it.copy(lanes = if (it.lanes == 4) 3 else 4) }
            }
            Spacer(Modifier.height(18.dp))
            ModeSelector(mode) { mode = it }
            Spacer(Modifier.height(12.dp))
            DifficultySelector(difficulty, chart?.charts?.filter { it.lanes == lanes }?.associate { it.difficulty to it.level }) { difficulty = it }
            Spacer(Modifier.height(14.dp))
            BestLine(best?.let { if (mode == GameMode.CLASSIC) "${it.tiles} teclas" else formatScore(it.score) }, best?.rank, best?.stars ?: 0, best?.plays ?: 0)
            Spacer(Modifier.height(132.dp))
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, cs.background.copy(alpha = 0.92f), cs.background)))
                .navigationBarsPadding()
                .padding(top = 28.dp, bottom = 14.dp),
        ) {
            PlayArea(analysis, onRetry = { app.charts.request(song) }) {
                app.settings.update { it.copy(lastDifficulty = difficulty, lastMode = mode) }
                app.preview.stop()
                onPlay(difficulty, mode)
            }
        }
    }
    if (albumSheet) AddToAlbumSheet(song, onDismiss = { albumSheet = false })
    if (paletteOpen) {
        SongColorsSheet(
            song = song,
            chart = chart,
            onSaved = { ThemeSeed.seed.value = it.main.seed },
            onDismiss = { paletteOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongFacts(song: Song, bpm: Float?, notes: Int?, lanes: Int, onToggleLanes: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        InfoPill(formatDuration(song.durationMs), Icons.Rounded.Schedule)
        InfoPill(song.format, Icons.Rounded.MusicNote)
        if (bpm != null) InfoPill("%.0f BPM".format(bpm), Icons.Rounded.GraphicEq)
        if (notes != null) InfoPill("$notes teclas", Icons.Rounded.Bolt)
        InfoPill("$lanes carriles", Icons.Rounded.ViewColumn, onClick = onToggleLanes)
    }
}

@Composable
private fun ModeSelector(mode: GameMode, onSelect: (GameMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GameMode.entries.forEach { m ->
            val selected = m == mode
            val cs = MaterialTheme.colorScheme
            val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh.copy(alpha = 0.9f), label = "modeBg")
            Surface(
                onClick = { onSelect(m) },
                shape = RoundedCornerShape(22.dp),
                color = bg,
                border = if (selected) BorderStroke(2.dp, cs.primary) else null,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (m == GameMode.CLASSIC) Icons.Rounded.Timer else Icons.Rounded.SportsEsports,
                            null,
                            tint = if (selected) cs.onPrimaryContainer else cs.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, minLines = 2, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun DifficultySelector(selected: Difficulty, levels: Map<Difficulty, Int>?, onSelect: (Difficulty) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Difficulty.entries.forEach { d ->
            val isSel = d == selected
            val cs = MaterialTheme.colorScheme
            val bg by animateColorAsState(if (isSel) cs.primary else cs.surfaceContainerHigh.copy(alpha = 0.9f), label = "diffBg")
            val fg = if (isSel) cs.onPrimary else cs.onSurface
            val scale by animateFloatAsState(if (isSel) 1f else 0.96f, label = "diffScale")
            Surface(
                onClick = { onSelect(d) },
                shape = RoundedCornerShape(18.dp),
                color = bg,
                modifier = Modifier.weight(1f).height((76 * scale).dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(levels?.get(d)?.toString() ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = fg)
                    Text(d.label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.85f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BestLine(best: String?, rank: String?, stars: Int, plays: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceContainer.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.EmojiEvents, null, tint = Color(0xFFFFD66E))
            Spacer(Modifier.width(12.dp))
            if (best == null) {
                Text("Todavía no la has jugado en esta dificultad", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Récord: $best", style = MaterialTheme.typography.titleSmall)
                    Text("$plays ${if (plays == 1) "partida" else "partidas"}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (rank != null && rank != "F") Text(rank, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = cs.primary)
                Spacer(Modifier.width(8.dp))
                repeat(stars) { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Color(0xFFFFD66E)) }
            }
        }
    }
}

@Composable
private fun PlayArea(state: AnalysisState, onRetry: () -> Unit, onPlay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AnimatedContent(state is AnalysisState.Ready, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "play") { ready ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ready) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(32.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("JUGAR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
            } else {
                when (state) {
                    is AnalysisState.Failed -> {
                        Text("No pude analizar esta canción: ${state.message}", color = cs.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRetry, shape = RoundedCornerShape(28.dp)) { Text("Reintentar") }
                    }
                    else -> {
                        val p = (state as? AnalysisState.Working)?.progress ?: 0f
                        val stage = (state as? AnalysisState.Working)?.stage ?: "Preparando…"
                        val animated by animateFloatAsState(p, label = "progress")
                        Surface(shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stage, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Creando la pista a partir del ritmo y la melodía. Solo pasa la primera vez.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
