package com.gatotiles.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatotiles.data.GameMode
import com.gatotiles.data.Song
import com.gatotiles.game.GameResult
import com.gatotiles.game.Judgment
import com.gatotiles.ui.components.formatScore
import com.gatotiles.util.ArtColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ResultsOverlay(
    result: GameResult,
    song: Song,
    colors: ArtColors,
    newBest: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    run: RunControls? = null,
) {
    val accent = Color(colors.accent)
    val gold = Color(0xFFFFD66E)
    val score = remember { Animatable(0f) }
    LaunchedEffect(Unit) { score.animateTo(result.score.toFloat(), tween(1500, easing = FastOutSlowInEasing)) }
    val rankScale = remember { Animatable(2.2f) }
    LaunchedEffect(Unit) {
        delay(250)
        rankScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val glow = TextStyle(shadow = Shadow(color = accent, blurRadius = 42f))

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Black.copy(alpha = 0.96f)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (result.cleared) "¡COMPLETADA!" else "FALLASTE",
                style = MaterialTheme.typography.headlineMedium.merge(glow),
                fontWeight = FontWeight.Black,
                color = if (result.cleared) Color.White else Color(Judgment.MISS.color),
            )
            if (!result.cleared && result.failReason != null) {
                Text(result.failReason.label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            if (run != null) {
                Text(
                    "Canción ${run.position} de ${run.total} · ${run.albumName}",
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${song.title} · ${result.difficulty.label} · ${result.mode.label}" + if (result.lanes != 4) " · ${result.lanes} carriles" else "",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            if (result.mode == GameMode.RHYTHM) {
                Text(
                    result.rank,
                    fontSize = 104.sp,
                    fontWeight = FontWeight.Black,
                    color = if (result.cleared) accent else Color.White.copy(alpha = 0.4f),
                    style = glow,
                    modifier = Modifier.scale(rankScale.value),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until 5) StarPop(i, i < result.stars, gold)
                }
            } else {
                Text(
                    "${result.tiles}",
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    style = glow,
                    modifier = Modifier.scale(rankScale.value),
                )
                Text("teclas de ${result.totalTiles}", color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 3) StarPop(i, i < result.stars, gold)
                    if (result.crowns > 0) {
                        Spacer(Modifier.width(10.dp))
                        for (i in 0 until result.crowns) {
                            Icon(Icons.Rounded.WorkspacePremium, "Corona", tint = gold, modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                formatScore(score.value.roundToInt()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text("precisión %.1f%%".format(result.accuracy), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (newBest) Badge("NUEVO RÉCORD", gold)
                if (result.allPerfect) Badge("ALL PERFECT", Color(Judgment.PERFECT.color))
                else if (result.fullCombo) Badge("FULL COMBO", accent)
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row {
                        Stat("PERFECT", result.perfect, Color(Judgment.PERFECT.color), Modifier.weight(1f))
                        Stat("GREAT", result.great, Color(Judgment.GREAT.color), Modifier.weight(1f))
                    }
                    Row {
                        Stat("GOOD", result.good, Color(Judgment.GOOD.color), Modifier.weight(1f))
                        Stat("MISS", result.miss, Color(Judgment.MISS.color), Modifier.weight(1f))
                    }
                    Row {
                        Stat("COMBO MÁX.", result.maxCombo, Color.White, Modifier.weight(1f))
                        Stat("TECLAS", result.tiles, Color.White, Modifier.weight(1f))
                    }
                    val off = result.meanOffsetMs.roundToInt()
                    val offText = when {
                        abs(off) <= 3 -> "Tu ritmo promedio: perfecto (${off} ms)"
                        off > 0 -> "Tu ritmo promedio: +$off ms (un poco tarde)"
                        else -> "Tu ritmo promedio: $off ms (un poco temprano)"
                    }
                    Text(offText, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    if (abs(off) > 25) {
                        Text(
                            "Si siempre te pasa, ajusta el desfase en Ajustes → Calibrar.",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            if (run != null) {
                RunButtons(run, accent, onRetry)
            } else {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Rounded.Replay, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Otra vez", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onExit, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Elegir otra canción", color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StarPop(index: Int, filled: Boolean, gold: Color) {
    val visible by produceState(false) {
        delay(700L + index * 170L)
        value = true
    }
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.StarOutline, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(36.dp))
        AnimatedVisibility(visible && filled, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()) {
            Icon(Icons.Rounded.Star, null, tint = gold, modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("$value", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Next song (with an optional countdown), retry and end, when playing an album. */
@Composable
private fun RunButtons(run: RunControls, accent: Color, onRetry: () -> Unit) {
    var left by remember { mutableIntStateOf(if (run.autoNext) 8 else -1) }
    LaunchedEffect(left) {
        if (left > 0) {
            delay(1000)
            left--
        } else if (left == 0) {
            run.onNext()
        }
    }
    Button(
        onClick = run.onNext,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Icon(if (run.nextTitle != null) Icons.Rounded.SkipNext else Icons.Rounded.EmojiEvents, null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                when {
                    run.nextTitle == null -> "Ver resumen del álbum"
                    left > 0 -> "Siguiente en $left s"
                    else -> "Siguiente canción"
                },
                fontWeight = FontWeight.Bold,
            )
            if (run.nextTitle != null) {
                Text(run.nextTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (left > 0) {
        TextButton(onClick = { left = -1 }) { Text("Esperar aquí", color = Color.White.copy(alpha = 0.8f)) }
    } else {
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = {
                left = -1
                onRetry()
            },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(Icons.Rounded.Replay, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Otra vez", color = Color.White)
        }
        OutlinedButton(onClick = run.onEnd, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).height(52.dp)) {
            Text("Terminar", color = Color.White)
        }
    }
}
