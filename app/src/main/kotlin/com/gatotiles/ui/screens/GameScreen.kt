package com.gatotiles.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.SongChart
import com.gatotiles.data.GameMode
import com.gatotiles.data.ResolvedColors
import com.gatotiles.game.GameResult
import com.gatotiles.game.GameSession
import com.gatotiles.game.GameUiState
import com.gatotiles.game.GameView
import kotlinx.coroutines.delay

/** Extra controls when the song is part of an album played song after song. */
class RunControls(
    /** 1-based. */
    val position: Int,
    val total: Int,
    val albumName: String,
    val nextTitle: String?,
    val autoNext: Boolean,
    val onResult: (GameResult) -> Unit,
    val onNext: () -> Unit,
    val onEnd: () -> Unit,
)

@Composable
fun GameScreen(songId: Long, difficulty: Difficulty, mode: GameMode, onExit: () -> Unit, run: RunControls? = null) {
    val app = GatoApp.app
    val context = LocalContext.current
    val song = remember(songId) { app.music.byId(songId) }
    val settings = remember { app.settings.current }
    var chart by remember { mutableStateOf<SongChart?>(null) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var colors by remember { mutableStateOf<ResolvedColors?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(songId) {
        if (song == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        app.preview.stop()
        art = app.art.load(song, 640)
        colors = app.colorsFor(song)
        chart = app.charts.load(song)
        if (chart == null) loadFailed = true
    }
    ImmersiveAndFast()

    if (loadFailed) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val c = chart
    val col = colors
    if (song == null || c == null || col == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(attempt) { GameSession(context, song, c, difficulty, mode, settings, col, art) }
    DisposableEffect(session) { onDispose { session.release() } }
    val ui by session.ui.collectAsStateWithLifecycle()
    var countdown by remember(session) { mutableIntStateOf(0) }
    var newBest by remember(session) { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) session.pause() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(ui) {
        val s = ui
        if (s is GameUiState.Finished) {
            if (!settings.autoPlay) newBest = app.scores.record(song.key, s.result)
            run?.onResult(s.result)
        }
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(650)
            countdown--
            if (countdown == 0) session.resume()
        }
    }
    BackHandler {
        when (ui) {
            GameUiState.Playing, GameUiState.Ready -> session.pause()
            else -> onExit()
        }
    }
    val density = LocalDensity.current
    session.topInset = WindowInsets.displayCutout.getTop(density).toFloat()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(session) {
            AndroidView(factory = { GameView(it, session) }, modifier = Modifier.fillMaxSize())
        }
        if ((ui == GameUiState.Playing || ui == GameUiState.Ready) && countdown == 0) {
            IconButton(
                onClick = { session.pause() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White),
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(12.dp)
                    .size(44.dp),
            ) { Icon(Icons.Rounded.Pause, "Pausa") }
        }
        AnimatedVisibility(ui == GameUiState.Paused && countdown == 0, enter = fadeIn(), exit = fadeOut()) {
            PauseMenu(
                title = song.title,
                subtitle = run?.let { "Canción ${it.position} de ${it.total} · ${it.albumName}" },
                onResume = { countdown = 3 },
                onRestart = { attempt++ },
                onSkip = run?.onNext,
                onExit = run?.onEnd ?: onExit,
            )
        }
        if (countdown > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                val anim = remember(countdown) { Animatable(1.6f) }
                LaunchedEffect(countdown) { anim.animateTo(1f, tween(300)) }
                Text("$countdown", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.scale(anim.value))
            }
        }
        val s = ui
        AnimatedVisibility(s is GameUiState.Finished, enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.96f), exit = fadeOut()) {
            if (s is GameUiState.Finished) {
                ResultsOverlay(
                    result = s.result,
                    song = song,
                    colors = col.main,
                    newBest = newBest,
                    onRetry = { attempt++ },
                    onExit = onExit,
                    run = run,
                )
            }
        }
        if (s is GameUiState.Error) {
            AlertDialog(
                onDismissRequest = onExit,
                confirmButton = { TextButton(onClick = onExit) { Text("Salir") } },
                title = { Text("Problema con el audio") },
                text = { Text(s.message) },
            )
        }
    }
}

@Composable
private fun PauseMenu(title: String, subtitle: String?, onResume: () -> Unit, onRestart: () -> Unit, onSkip: (() -> Unit)?, onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("PAUSA", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White)
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onResume, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.size(8.dp))
                Text("Continuar", fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(onClick = onRestart, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Replay, null)
                Spacer(Modifier.size(8.dp))
                Text("Reiniciar")
            }
            if (onSkip != null) {
                FilledTonalButton(onClick = onSkip, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Rounded.SkipNext, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Saltar canción")
                }
            }
            OutlinedButton(onClick = onExit, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(if (onSkip != null) "Terminar álbum" else "Salir", color = Color.White)
            }
        }
    }
}

/** Fullscreen while playing and the highest refresh rate the screen offers. */
@Composable
private fun ImmersiveAndFast() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val oldMode = window?.attributes?.preferredDisplayModeId ?: 0
        val oldCutout = if (Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else null
        if (window != null) {
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            val current = display?.mode
            val best = display?.supportedModes
                ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
                ?.maxByOrNull { it.refreshRate }
            val attrs = window.attributes
            if (best != null) attrs.preferredDisplayModeId = best.modeId
            if (Build.VERSION.SDK_INT >= 28) attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = oldMode
                if (Build.VERSION.SDK_INT >= 28 && oldCutout != null) attrs.layoutInDisplayCutoutMode = oldCutout
                window.attributes = attrs
            }
        }
    }
}
