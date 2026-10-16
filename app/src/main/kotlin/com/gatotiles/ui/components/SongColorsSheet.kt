package com.gatotiles.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.ui.theme.FollowAppTheme
import com.gatotiles.analyzer.model.SongChart
import com.gatotiles.data.ColorFlow
import com.gatotiles.data.CoverPalette
import com.gatotiles.data.ResolvedColors
import com.gatotiles.data.Song
import com.gatotiles.data.SongColorConfig
import com.gatotiles.game.ColorTimeline
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil
import kotlin.math.max

/**
 * "Paleta de colores" of a song (Namida-like): the cover's colors, mixes and your own, how
 * the color moves during the song, and a preview of the whole song in those colors.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongColorsSheet(song: Song, chart: SongChart?, onSaved: (ResolvedColors) -> Unit, onDismiss: () -> Unit) {
    val app = GatoApp.app
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val saved = remember(song.key) { app.songColors.get(song.key) }
    val cover by produceState<CoverPalette?>(null, song.key) { value = app.art.palette(song) }
    val coverColors by produceState<ArtColors?>(app.art.cachedColors(song), song.key) { value = app.art.colors(song) }
    var flow by remember { mutableStateOf(saved.flow ?: settings.colorFlow) }
    val chosen = remember { saved.colors.toMutableStateList() }
    val custom = remember { saved.custom.toMutableStateList() }
    val hidden = remember { saved.hidden.toMutableStateList() }
    var picker by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme

    fun config() = SongColorConfig(
        flow = if (flow == settings.colorFlow && saved.flow == null) null else flow,
        colors = chosen.toList(),
        custom = custom.toList(),
        hidden = hidden.toList(),
    )
    val pal = cover ?: CoverPalette.Empty
    val resolved = coverColors?.let { ResolvedColors.resolve(config(), settings.colorFlow, pal, it) }
    val multi = flow != ColorFlow.FIXED

    fun tap(c: Int) {
        if (!multi) {
            chosen.clear()
            chosen.add(c)
        } else if (c in chosen) {
            chosen.remove(c)
        } else if (chosen.size < ResolvedColors.MAX_COLORS) {
            chosen.add(c)
        }
    }

    FollowAppTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .animateContentSize(),
            ) {
                Text("Paleta de colores", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(song.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(16.dp))

                SectionLabel("Durante la canción")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ColorFlow.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = flow == f,
                            onClick = {
                                flow = f
                                if (f == ColorFlow.FIXED && chosen.size > 1) {
                                    val first = chosen.first()
                                    chosen.clear()
                                    chosen.add(first)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, ColorFlow.entries.size),
                        ) { Text(f.label, maxLines = 1) }
                    }
                }
                AnimatedContent(flow, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "flowText") { f ->
                    Text(
                        f.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))

                // Preview of the whole song.
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0B0A10), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Así se verá", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f))
                            resolved?.palettes?.forEach { p ->
                                MiniTile(p, Modifier.size(width = 22.dp, height = 30.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (resolved != null) SongColorPreview(resolved, chart, song.durationMs)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            previewCaption(resolved, chart),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Colors in use.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(if (multi) "Usados (en orden)" else "Usado", Modifier.weight(1f))
                    if (chosen.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = cs.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (pal.swatches.isEmpty()) "Automático" else "Automático, de la portada", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        }
                    }
                }
                if (resolved != null) {
                    UsedColors(resolved, chosen.isEmpty(), multi) { i -> if (chosen.isNotEmpty()) chosen.removeAt(i) }
                }
                if (multi) {
                    Text(
                        if (chosen.isEmpty()) "Toca colores de la paleta para elegirlos tú (hasta ${ResolvedColors.MAX_COLORS}). Toca uno de arriba para quitarlo."
                        else "${chosen.size} de ${ResolvedColors.MAX_COLORS} · toca uno de arriba para quitarlo",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))

                // Palette.
                SectionLabel("Paleta")
                // Songs without a cover get a rainbow to start from.
            val base = pal.swatches.ifEmpty { PRESETS }
            val colors = (base + custom).filter { it !in hidden }.distinct()
                Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f))) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (c in colors) {
                            Swatch(
                                color = c,
                                order = if (multi) chosen.indexOf(c) else if (chosen.firstOrNull() == c) 0 else -1,
                                numbered = multi,
                                onClick = { tap(c) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    chosen.remove(c)
                                    if (c in custom) custom.remove(c) else hidden.add(c)
                                },
                            )
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceContainerHighest)
                                .combinedClickable(onClick = { picker = true }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, "Añadir color", tint = cs.primary) }
                    }
                }
                if (pal.mixes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mix", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (m in pal.mixes) {
                                Swatch(m, if (multi) chosen.indexOf(m) else if (chosen.firstOrNull() == m) 0 else -1, multi, onClick = { tap(m) }, onLongClick = null)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "• Toca un color o un mix para usarlo\n• Mantén presionado un color para quitarlo de la paleta",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = {
                        flow = settings.colorFlow
                        chosen.clear()
                        custom.clear()
                        hidden.clear()
                    }) { Icon(Icons.Rounded.RestartAlt, "Restablecer") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) { Text("Cancelar") }
                    Button(
                        onClick = {
                            val cfg = config()
                            app.songColors.set(song.key, cfg)
                            resolved?.let(onSaved)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.3f).height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                    ) { Text("Confirmar", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (picker) {
        ColorPickerDialog(
            initial = chosen.lastOrNull() ?: coverColors?.seed ?: ArtColors.DEFAULT_SEED,
            onPick = { c ->
                if (c !in custom) custom.add(c)
                hidden.remove(c)
                tap(c)
                picker = false
            },
            onDismiss = { picker = false },
        )
    }
}

private val PRESETS: List<Int> =
    (0 until 12).map { ColorUtil.fromOklch(0.72, 0.17, 25.0 + it * 30.0) } + listOf(0xFFF2F2F2.toInt(), 0xFF9E9E9E.toInt())

private fun previewCaption(r: ResolvedColors?, chart: SongChart?): String {
    if (r == null) return ""
    return when {
        r.flow == ColorFlow.FIXED || r.palettes.size == 1 ->
            if (r.flow != ColorFlow.FIXED) "Con un solo color no hay cambios: elige dos o más." else "El mismo color de principio a fin."
        r.flow == ColorFlow.SECTIONS && chart == null -> "Las partes aparecen cuando la pista esté lista."
        r.flow == ColorFlow.SECTIONS -> {
            val parts = chart!!.sections.size
            val kinds = chart.sections.map { it.label }.distinct().size
            if (parts <= 1) "No encontré partes distintas en esta canción." else "$parts partes, $kinds tipos distintos: las que se repiten tienen el mismo color."
        }
        else -> "Pasa por ${r.palettes.size} colores de principio a fin."
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** The song's waveform painted with the color each moment will have. */
@Composable
fun SongColorPreview(r: ResolvedColors, chart: SongChart?, durationMs: Long, modifier: Modifier = Modifier) {
    val duration = chart?.durationMs ?: durationMs.toInt()
    val timeline = remember(r, chart) { ColorTimeline(r.palettes, r.flow, chart?.sections ?: emptyList(), max(1, duration)) }
    val wave = chart?.waveform
    val sections = if (r.flow == ColorFlow.SECTIONS) chart?.sections else null
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val bars = 64
        val step = size.width / bars
        val stroke = max(2f, step * 0.55f)
        val mid = size.height / 2 + 4.dp.toPx()
        for (i in 0 until bars) {
            val v = if (wave.isNullOrEmpty()) {
                0.35f
            } else {
                val a = i * wave.size / bars
                val b = max(a + 1, (i + 1) * wave.size / bars)
                var m = 0
                for (k in a until minOf(b, wave.size)) m = max(m, wave[k])
                (0.12f + 0.88f * m / 100f)
            }
            val t = (i + 0.5) / bars * duration
            val h = (size.height - 10.dp.toPx() - stroke) * v / 2
            val x = step * (i + 0.5f)
            drawLine(Color(timeline.accentAt(t)), Offset(x, mid - h), Offset(x, mid + h), stroke, StrokeCap.Round)
        }
        // Part boundaries.
        if (sections != null) {
            for (s in sections.drop(1)) {
                val x = s.t.toFloat() / duration * size.width
                drawLine(Color.White.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun UsedColors(r: ResolvedColors, auto: Boolean, multi: Boolean, onRemove: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!multi) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(r.seeds.first()),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {}
            MiniTile(r.main, Modifier.size(width = 34.dp, height = 46.dp))
        } else {
            r.seeds.forEachIndexed { i, seed ->
                Box(contentAlignment = Alignment.TopEnd) {
                    Surface(
                        onClick = { onRemove(i) },
                        enabled = !auto,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(seed).copy(alpha = if (auto) 0.75f else 1f),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black,
                                color = if (ColorUtil.toOklch(seed).l > 0.6) Color.Black else Color.White,
                            )
                        }
                    }
                    if (!auto) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, null, Modifier.size(11.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(color: Int, order: Int, numbered: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val selected = order >= 0
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "swatch")
    val cs = MaterialTheme.colorScheme
    val light = ColorUtil.toOklch(color).l > 0.62
    Box(
        Modifier
            .size(42.dp)
            .border(if (selected) 2.5.dp else 0.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .padding(((1f - scale) * 21).dp)
            .clip(CircleShape)
            .background(Color(color))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (numbered) {
                Text("${order + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = if (light) Color.Black else Color.White)
            } else {
                Icon(Icons.Rounded.Check, null, tint = if (light) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
