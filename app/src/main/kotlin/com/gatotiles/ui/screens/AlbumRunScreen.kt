package com.gatotiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.data.AlbumRun
import com.gatotiles.data.AnalysisState
import com.gatotiles.data.GameMode
import com.gatotiles.ui.ThemeSeed
import com.gatotiles.ui.components.AlbumArt
import com.gatotiles.ui.components.formatScore

/** Plays the songs of an album one after another, then shows how it went. */
@Composable
fun AlbumRunScreen(onExit: () -> Unit) {
    val app = GatoApp.app
    var run by remember { mutableStateOf(app.run) }
    val r = run
    if (r == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val index by r.index.collectAsStateWithLifecycle()
    val finished by r.finished.collectAsStateWithLifecycle()
    if (finished) {
        AlbumSummary(
            r,
            onAgain = {
                val again = AlbumRun(r.albumId, r.name, r.songs, r.difficulty, r.mode, r.autoNext)
                app.run = again
                run = again
            },
            onExit = {
                app.run = null
                onExit()
            },
        )
        return
    }
    val song = r.songs[index]
    val analysis by remember(song.key) { app.charts.request(song) }.collectAsStateWithLifecycle()
    LaunchedEffect(song.key) { ThemeSeed.seed.value = app.colorsFor(song).main.seed }
    when (val a = analysis) {
        is AnalysisState.Ready -> {
            // While this song is played, the next one gets its track ready.
            LaunchedEffect(index) { r.next?.let { app.charts.request(it) } }
            key(index) {
                GameScreen(
                    songId = song.id,
                    difficulty = r.difficulty,
                    mode = r.mode,
                    onExit = { r.finish() },
                    run = RunControls(
                        position = index + 1,
                        total = r.songs.size,
                        albumName = r.name,
                        nextTitle = r.next?.title,
                        autoNext = r.autoNext,
                        onResult = { r.record(it) },
                        onNext = { r.advance() },
                        onEnd = { r.finish() },
                    ),
                )
            }
        }
        else -> Preparing(r, index, a, onSkip = { r.advance() }, onEnd = { r.finish() })
    }
}

@Composable
private fun Preparing(r: AlbumRun, index: Int, state: AnalysisState, onSkip: () -> Unit, onEnd: () -> Unit) {
    val song = r.songs[index]
    val cs = MaterialTheme.colorScheme
    BackHandler { onEnd() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.5f), cs.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Canción ${index + 1} de ${r.songs.size}", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            AlbumArt(song, Modifier.size(180.dp), corner = 28.dp, sizePx = 400)
            Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (state is AnalysisState.Failed) {
                Text("No pude crear la pista: ${state.message}", color = cs.error, textAlign = TextAlign.Center)
                Button(onClick = onSkip, shape = RoundedCornerShape(28.dp)) {
                    Icon(Icons.Rounded.SkipNext, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Saltar esta canción")
                }
            } else {
                val p = (state as? AnalysisState.Working)?.progress ?: 0f
                val animated by animateFloatAsState(p, label = "prep")
                Text((state as? AnalysisState.Working)?.stage ?: "Preparando la pista…", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { animated },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth(0.8f).height(6.dp),
                )
                Text(
                    "Es la primera vez que se juega: la pista se crea una sola vez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(onClick = onEnd) { Text("Terminar álbum") }
        }
    }
}

@Composable
private fun AlbumSummary(r: AlbumRun, onAgain: () -> Unit, onExit: () -> Unit) {
    val results by r.results.collectAsStateWithLifecycle()
    val reached by r.index.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val gold = Color(0xFFFFD66E)
    val played = results.size
    val cleared = results.values.count { it.cleared }
    val total = results.values.sumOf { it.score }
    val stars = results.values.sumOf { it.stars }
    val allDone = played == r.songs.size && cleared == played
    BackHandler { onExit() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to cs.primaryContainer.copy(alpha = 0.6f), 0.45f to cs.background, 1f to cs.background)),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.EmojiEvents, null, tint = gold, modifier = Modifier.size(56.dp))
                    Text(
                        if (allDone) "¡Álbum completado!" else "Álbum terminado",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "${r.name} · ${r.difficulty.label} · ${r.mode.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryStat("Jugadas", "$played/${r.songs.size}", Modifier.weight(1f))
                        SummaryStat("Completadas", "$cleared", Modifier.weight(1f))
                        SummaryStat(if (r.mode == GameMode.CLASSIC) "Teclas" else "Puntos", if (r.mode == GameMode.CLASSIC) "${results.values.sumOf { it.tiles }}" else formatScore(total), Modifier.weight(1.3f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, null, tint = gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$stars estrellas en total", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            itemsIndexed(r.songs) { i, song ->
                val res = results[i]
                Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center)
                        AlbumArt(song, Modifier.size(46.dp), corner = 12.dp, sizePx = 160)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    res == null && i < reached -> "Saltada"
                                    res == null -> "Sin jugar"
                                    !res.cleared -> "Fallaste · " + if (r.mode == GameMode.CLASSIC) "${res.tiles} teclas" else formatScore(res.score)
                                    r.mode == GameMode.CLASSIC -> "${res.tiles} teclas"
                                    else -> formatScore(res.score) + " · %.1f%%".format(res.accuracy)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (res != null && !res.cleared) cs.error else cs.onSurfaceVariant,
                            )
                        }
                        if (res != null) {
                            if (r.mode == GameMode.RHYTHM && res.cleared) {
                                Text(res.rank, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = cs.primary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Row {
                                repeat(res.stars) { Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = gold) }
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, cs.background.copy(alpha = 0.95f), cs.background)))
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onAgain, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.Replay, null)
                Spacer(Modifier.width(8.dp))
                Text("Jugar el álbum otra vez", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onExit, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Volver al álbum")
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}
