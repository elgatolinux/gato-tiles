package com.gatotiles.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatotiles.GatoApp
import com.gatotiles.data.Song
import kotlin.math.max
import kotlin.math.sin

@Composable
fun rememberArt(song: Song?, sizePx: Int): ImageBitmap? {
    val app = GatoApp.app
    val bmp by produceState(initialValue = song?.let { app.art.cached(it, sizePx)?.asImageBitmap() }, song?.id, sizePx) {
        value = song?.let { app.art.load(it, sizePx)?.asImageBitmap() }
    }
    return bmp
}

/** Album cover with a rounded shape and a gradient placeholder when the file has no art. */
@Composable
fun AlbumArt(song: Song?, modifier: Modifier = Modifier, corner: Dp = 12.dp, sizePx: Int = 192) {
    val bmp = rememberArt(song, sizePx)
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = cs.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }
    }
}

/**
 * Namida-style waveform made of rounded bars. [values] are 0..100 (from the analysis);
 * null shows an animated placeholder while the song is being analyzed.
 */
@Composable
fun Waveform(
    values: List<Int>?,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    bars: Int = 72,
    active: Color = MaterialTheme.colorScheme.primary,
    inactive: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
) {
    val anim = rememberInfiniteTransition(label = "wave")
    val phase by anim.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val step = size.width / bars
        val stroke = max(2f, step * 0.55f)
        val mid = size.height / 2
        for (i in 0 until bars) {
            val v = if (values.isNullOrEmpty()) {
                0.12f + 0.1f * (1 + sin(phase + i * 0.35f)) / 2
            } else {
                val a = i * values.size / bars
                val b = max(a + 1, (i + 1) * values.size / bars)
                var m = 0
                for (k in a until minOf(b, values.size)) m = max(m, values[k])
                (0.1f + 0.9f * (m / 100f)).coerceIn(0.06f, 1f)
            }
            val h = (size.height - stroke) * v / 2
            val x = step * (i + 0.5f)
            drawLine(
                color = if ((i + 0.5f) / bars <= progress) active else inactive,
                start = Offset(x, mid - h),
                end = Offset(x, mid + h),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Rounded settings group (Namida look). */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = trailing)
        }
    }
}

@Composable
fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Small pill with an optional icon, used for song facts (BPM, format, level...). */
@Composable
fun InfoPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = cs.primary)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (onClick == null) {
        Surface(shape = RoundedCornerShape(50), color = cs.surfaceContainerHighest.copy(alpha = 0.8f), modifier = modifier, content = content)
    } else {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)),
            modifier = modifier,
            content = content,
        )
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatScore(v: Int): String {
    val s = v.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return sb.toString()
}
