package com.gatotiles.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gatotiles.GatoApp
import com.gatotiles.audio.SongPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val FIRST_BEAT_MS = 1000.0
private const val BEAT_MS = 600.0 // 100 bpm
private const val BEATS = 48
private const val NEEDED_TAPS = 16

/** Writes a metronome WAV (accent every 4 beats) into the cache, played through the game's audio path. */
private fun metronomeFile(context: Context): File {
    val f = File(context.cacheDir, "metronomo.wav")
    if (f.exists()) return f
    val sr = 44100
    val total = ((FIRST_BEAT_MS + BEAT_MS * BEATS + 1000) / 1000.0 * sr).toInt()
    val pcm = ShortArray(total)
    for (k in 0 until BEATS) {
        val start = ((FIRST_BEAT_MS + k * BEAT_MS) / 1000.0 * sr).toInt()
        val freq = if (k % 4 == 0) 1800.0 else 1200.0
        for (i in 0 until (0.04 * sr).toInt()) {
            val t = i.toDouble() / sr
            val v = 0.8 * sin(2 * PI * freq * t) * exp(-t / 0.008)
            if (start + i < total) pcm[start + i] = (v * 32767).toInt().toShort()
        }
    }
    DataOutputStream(FileOutputStream(f)).use { o ->
        fun le32(v: Int) = o.writeInt(Integer.reverseBytes(v))
        fun le16(v: Int) = o.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        o.writeBytes("RIFF"); le32(36 + total * 2); o.writeBytes("WAVE")
        o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sr); le32(sr * 2); le16(2); le16(16)
        o.writeBytes("data"); le32(total * 2)
        for (s in pcm) le16(s.toInt())
    }
    return f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val app = GatoApp.app
    val context = LocalContext.current
    var player by remember { mutableStateOf<SongPlayer?>(null) }
    val taps = remember { mutableStateListOf<Double>() }
    var result by remember { mutableStateOf<Int?>(null) }
    var pulse by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    fun stop() {
        player?.release()
        player = null
    }
    fun start() {
        stop()
        taps.clear()
        result = null
        app.preview.stop()
        val p = SongPlayer(context.applicationContext, Uri.fromFile(metronomeFile(context)))
        p.start(400)
        player = p
    }
    fun onTap(uptimeMs: Long) {
        val p = player ?: return
        if (result != null) return
        val song = p.songTimeMs(uptimeMs * 1_000_000L)
        val k = ((song - FIRST_BEAT_MS) / BEAT_MS).roundToInt()
        if (k < 2 || k >= BEATS) return
        val off = song - (FIRST_BEAT_MS + k * BEAT_MS)
        if (abs(off) > 250) return
        taps.add(off)
        if (taps.size >= NEEDED_TAPS) {
            val sorted = taps.sorted()
            result = sorted[sorted.size / 2].roundToInt()
            stop()
        }
    }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val song = p.songTimeMs()
            if (song > FIRST_BEAT_MS + BEAT_MS * BEATS + 500 && result == null) {
                stop()
                break
            }
            val since = ((song - FIRST_BEAT_MS) % BEAT_MS + BEAT_MS) % BEAT_MS
            pulse = if (song >= FIRST_BEAT_MS - 50) exp(-since / 140.0).toFloat() else 0f
        }
    }

    val cs = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Pon el volumen y los auriculares que usas para jugar. Toca el círculo justo cuando " +
                    "suene cada clic, guiándote por el sonido.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .size(220.dp)
                    .scale(1f + 0.12f * pulse)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.25f + 0.6f * pulse))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                for (ch in e.changes) {
                                    if (ch.pressed && !ch.previousPressed) {
                                        onTap(ch.uptimeMillis)
                                        ch.consume()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        result != null -> "¡Listo!"
                        player != null -> "${taps.size} / $NEEDED_TAPS"
                        else -> "Toca Empezar"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onPrimaryContainer,
                )
            }
            // Spread of the taps around the beat (-150..+150 ms).
            Canvas(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh)) {
                val mid = size.width / 2
                drawLine(cs.onSurfaceVariant.copy(alpha = 0.5f), Offset(mid, 6f), Offset(mid, size.height - 6f), 3f)
                for (t in taps) {
                    val x = mid + (t / 150.0 * (size.width / 2 - 12)).toFloat().coerceIn(-mid + 12, mid - 12)
                    drawCircle(cs.primary, 9f, Offset(x, size.height / 2))
                }
            }
            Text("temprano  ←  →  tarde", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            val r = result
            if (r != null) {
                Text(
                    if (r > 0) "Tocas $r ms tarde en promedio" else if (r < 0) "Tocas ${-r} ms temprano en promedio" else "¡Tu sincronía es perfecta!",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = {
                        app.settings.update { it.copy(inputOffsetMs = r.coerceIn(-200, 200)) }
                        onBack()
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text("Usar este desfase (${if (r > 0) "+" else ""}$r ms)") }
                OutlinedButton(onClick = { start() }, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) { Text("Repetir") }
            } else {
                Button(
                    onClick = { start() },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text(if (player == null) "Empezar" else "Reiniciar") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Desfase actual: ${app.settings.current.inputOffsetMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
