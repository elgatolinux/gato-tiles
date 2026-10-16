package com.gatotiles.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.BuildConfig
import com.gatotiles.GatoApp
import com.gatotiles.analyzer.model.LANE_OPTIONS
import com.gatotiles.data.ColorFlow
import com.gatotiles.data.SpeedFlow
import com.gatotiles.data.ThemeMode
import com.gatotiles.data.TrackStyle
import com.gatotiles.ui.components.SettingRow
import com.gatotiles.ui.components.SettingsGroup
import com.gatotiles.ui.components.SwitchRow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onCalibrate: () -> Unit, onFolders: () -> Unit) {
    val app = GatoApp.app
    val s by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(app.charts.cacheSizeBytes()) }
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
                scrollBehavior = behavior,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
        ) {
            item {
                SettingsGroup("Apariencia") {
                    ChoiceRow(Icons.Rounded.DarkMode, "Tema", ThemeMode.entries.map { it.label }, s.themeMode.ordinal) { i ->
                        app.settings.update { it.copy(themeMode = ThemeMode.entries[i]) }
                    }
                    SwitchRow(Icons.Rounded.ColorLens, "Colores de la portada", "La app toma los colores de la canción que estás viendo", s.dynamicColor) { v ->
                        app.settings.update { it.copy(dynamicColor = v) }
                    }
                    ChoiceRow(
                        Icons.Rounded.Gradient, "Color durante la partida", ColorFlow.entries.map { it.label }, s.colorFlow.ordinal,
                        subtitle = "Para todas las canciones. Cada una puede tener sus propios colores en su paleta (botón de la paleta en la canción).",
                    ) { i ->
                        app.settings.update { it.copy(colorFlow = ColorFlow.entries[i]) }
                    }
                }
            }
            item {
                SettingsGroup("Juego") {
                    ChoiceRow(Icons.Rounded.ViewInAr, "Vista de la pista", TrackStyle.entries.map { it.label }, s.trackStyle.ordinal) { i ->
                        app.settings.update { it.copy(trackStyle = TrackStyle.entries[i]) }
                    }
                    ChoiceRow(
                        Icons.Rounded.ViewColumn, "Carriles", LANE_OPTIONS.map { "$it carriles" }, LANE_OPTIONS.indexOf(s.lanes).coerceAtLeast(0),
                        subtitle = "Con 3 las teclas son más anchas; el ritmo es el mismo",
                    ) { i ->
                        app.settings.update { it.copy(lanes = LANE_OPTIONS[i]) }
                    }
                    SliderRow(
                        Icons.Rounded.Speed, "Velocidad de las teclas", "${s.scrollSpeed}  ·  ${s.approachMs} ms de caída",
                        s.scrollSpeed.toFloat(), 1f..10f, 8,
                    ) { v -> app.settings.update { it.copy(scrollSpeed = v.roundToInt()) } }
                    ChoiceRow(
                        Icons.AutoMirrored.Rounded.TrendingUp, "Velocidad variable", SpeedFlow.entries.map { it.label }, s.speedFlow.ordinal,
                        subtitle = "Las teclas caen más rápido en las partes más movidas de la canción (estribillos, clímax) y vuelven a tu velocidad en las tranquilas",
                    ) { i ->
                        app.settings.update { it.copy(speedFlow = SpeedFlow.entries[i]) }
                    }
                    ChoiceRow(Icons.Rounded.AutoAwesome, "Efectos visuales", listOf("Bajos", "Medios", "Altos"), s.effects) { i ->
                        app.settings.update { it.copy(effects = i) }
                    }
                    SwitchRow(Icons.Rounded.Shield, "Sin fallar (modo Ritmo)", "La canción sigue aunque se acabe la vida", s.noFail) { v ->
                        app.settings.update { it.copy(noFail = v) }
                    }
                    SwitchRow(Icons.Rounded.Timer, "Mostrar TEMPRANO / TARDE", "Indica si tocaste antes o después del momento exacto", s.earlyLate) { v ->
                        app.settings.update { it.copy(earlyLate = v) }
                    }
                    SwitchRow(Icons.Rounded.Vibration, "Vibrar al tocar", null, s.vibration) { v ->
                        app.settings.update { it.copy(vibration = v) }
                    }
                    SwitchRow(Icons.Rounded.Insights, "Mostrar FPS", null, s.showFps) { v ->
                        app.settings.update { it.copy(showFps = v) }
                    }
                    SwitchRow(Icons.Rounded.SmartToy, "Modo demostración", "El juego toca solo, perfecto: sirve para ver si la pista va con la música (no guarda récords)", s.autoPlay) { v ->
                        app.settings.update { it.copy(autoPlay = v) }
                    }
                }
            }
            item {
                SettingsGroup("Sincronización") {
                    SettingRow(
                        Icons.Rounded.Headphones,
                        "Calibrar",
                        "Toca al ritmo de un metrónomo para medir el retraso de tu teléfono o auriculares",
                        onClick = onCalibrate,
                    )
                    SliderRow(
                        Icons.Rounded.TouchApp, "Desfase de toque", "${signed(s.inputOffsetMs)} ms  ·  positivo si tocas tarde",
                        s.inputOffsetMs.toFloat(), -200f..200f, 79,
                    ) { v -> app.settings.update { it.copy(inputOffsetMs = (v / 5).roundToInt() * 5) } }
                    SliderRow(
                        Icons.Rounded.Visibility, "Desfase visual", "${signed(s.visualOffsetMs)} ms  ·  positivo retrasa las teclas",
                        s.visualOffsetMs.toFloat(), -200f..200f, 79,
                    ) { v -> app.settings.update { it.copy(visualOffsetMs = (v / 5).roundToInt() * 5) } }
                }
            }
            item {
                SettingsGroup("Biblioteca") {
                    SettingRow(
                        Icons.Rounded.Folder,
                        "Carpetas",
                        if (s.folderRules.isAutomatic) "Automático: toda tu música, sin notas de voz ni grabaciones" else "Carpetas elegidas por ti",
                        onClick = onFolders,
                    )
                    SliderRow(
                        Icons.Rounded.MusicNote, "Duración mínima", "${s.minDurationSec} s (ignora audios más cortos)",
                        s.minDurationSec.toFloat(), 10f..120f, 21,
                    ) { v -> app.settings.update { it.copy(minDurationSec = (v / 5).roundToInt() * 5) } }
                    SettingRow(Icons.Rounded.Refresh, "Volver a buscar música", null, onClick = {
                        scope.launch {
                            app.music.scan(s.minDurationSec * 1000L, s.folderRules)
                            app.charts.refreshCachedKeys(app.music.songs.value)
                        }
                    })
                    SwitchRow(Icons.Rounded.Headphones, "Vista previa automática", "Suena la parte más intensa al abrir una canción", s.autoPreview) { v ->
                        app.settings.update { it.copy(autoPreview = v) }
                    }
                    SettingRow(
                        Icons.Rounded.CleaningServices,
                        "Borrar pistas generadas",
                        "Ocupan %.1f MB; se vuelven a crear al jugar".format(cacheBytes / 1_048_576.0),
                        onClick = { confirmClear = true },
                    )
                }
            }
            item {
                SettingsGroup("Acerca de") {
                    SettingRow(
                        Icons.Rounded.Info,
                        "Gato Tiles ${BuildConfig.VERSION_NAME}",
                        "Pistas generadas en el teléfono a partir del ritmo y la melodía. Fuente Lexend Deca (SIL OFL 1.1).",
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar todas las pistas?") },
            text = { Text("Se volverán a generar cuando abras cada canción. Tus récords no se borran.") },
            confirmButton = {
                TextButton(onClick = {
                    app.charts.clearAll()
                    cacheBytes = app.charts.cacheSizeBytes()
                    confirmClear = false
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selected: Int,
    subtitle: String? = null,
    onSelect: (Int) -> Unit,
) {
    Column {
        SettingRow(icon, title, subtitle)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        SettingRow(icon, title, subtitle)
        Row(Modifier.padding(start = 68.dp, end = 20.dp)) {
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onChange(local) },
                valueRange = range,
                steps = steps,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
