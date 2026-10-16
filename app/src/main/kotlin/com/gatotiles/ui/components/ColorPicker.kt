package com.gatotiles.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil

/** HSV picker (saturation/brightness square + hue bar + hex code) to add a color to the palette. */
@Composable
fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hex by remember { mutableStateOf(ColorUtil.toHex(initial)) }
    var editingHex by remember { mutableStateOf(false) }
    if (!editingHex) hex = ColorUtil.toHex(color)
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SatValBox(hue, sat, value) { s, v ->
                    editingHex = false
                    sat = s
                    value = v
                }
                HueBar(hue) {
                    editingHex = false
                    hue = it
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(2.dp, cs.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    MiniTile(ArtColors.fromSeed(color, neutral = true), Modifier.size(width = 34.dp, height = 46.dp))
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            editingHex = true
                            hex = t.take(7)
                            ColorUtil.parseHex(t)?.let { c ->
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV(c, out)
                                hue = out[0]
                                sat = out[1]
                                value = out[2]
                            }
                        },
                        singleLine = true,
                        label = { Text("Código") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "En el juego las teclas brillan: los colores oscuros se ven más claros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Añadir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SatValBox(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun report(p: Offset) = onChange((p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f))
                    report(down.position)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        report(ch.position)
                        ch.consume()
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val p = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.White, 11.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), p, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val rainbow = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun report(p: Offset) = onChange((p.x / size.width).coerceIn(0f, 1f) * 359.9f)
                    report(down.position)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        report(ch.position)
                        ch.consume()
                    }
                }
            },
    ) {
        val r = size.height / 2
        drawRoundRect(Brush.horizontalGradient(rainbow), cornerRadius = CornerRadius(r, r))
        val x = hue / 360f * size.width
        drawCircle(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))), r - 3.dp.toPx(), Offset(x.coerceIn(r, size.width - r), r))
        drawCircle(Color.White, r - 2.dp.toPx(), Offset(x.coerceIn(r, size.width - r), r), style = Stroke(3.dp.toPx()))
    }
}

/** A tiny game tile in the given colors (same look as the real ones). */
@Composable
fun MiniTile(colors: ArtColors, modifier: Modifier = Modifier) {
    val accent = Color(colors.accent)
    val accent2 = Color(colors.accent2)
    Canvas(modifier) {
        val pad = size.width * 0.12f
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val rad = CornerRadius(w * 0.2f, w * 0.2f)
        // Glow.
        drawRoundRect(accent.copy(alpha = 0.28f), Offset(pad * 0.3f, pad * 0.3f), Size(size.width - pad * 0.6f, size.height - pad * 0.6f), CornerRadius(w * 0.32f, w * 0.32f))
        drawRoundRect(
            Brush.verticalGradient(
                0f to Color(0xFF1D1A28),
                0.6f to Color(0xFF0C0B12),
                1f to Color(ColorUtil.lerp(0xFF0C0B12.toInt(), colors.accent, 0.18f)),
                startY = pad,
                endY = pad + h,
            ),
            Offset(pad, pad),
            Size(w, h),
            rad,
        )
        val sw = 1.6.dp.toPx()
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(ColorUtil.lerp(colors.accent, 0xFFFFFFFF.toInt(), 0.35f)), accent2), startY = pad, endY = pad + h),
            Offset(pad + sw / 2, pad + sw / 2),
            Size(w - sw, h - sw),
            rad,
            style = Stroke(sw),
        )
        val slitW = w * 0.5f
        val slitH = 2.5.dp.toPx()
        val cy = pad + h / 2
        drawRoundRect(accent.copy(alpha = 0.5f), Offset(size.width / 2 - slitW / 2 - 2, cy - slitH), Size(slitW + 4, slitH * 2), CornerRadius(slitH, slitH))
        drawRoundRect(Color(ColorUtil.lerp(colors.accent, 0xFFFFFFFF.toInt(), 0.7f)), Offset(size.width / 2 - slitW / 2, cy - slitH / 2), Size(slitW, slitH), CornerRadius(slitH, slitH))
    }
}
