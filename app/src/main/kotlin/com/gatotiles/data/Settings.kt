package com.gatotiles.data

import android.content.Context
import com.gatotiles.analyzer.model.Difficulty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro"), AMOLED("AMOLED") }

@Serializable
enum class TrackStyle(val label: String) { PERSPECTIVE("3D"), FLAT("Plana") }

@Serializable
enum class GameMode(val label: String, val description: String) {
    CLASSIC("Clásico", "Toca solo las teclas: un error y se acaba"),
    RHYTHM("Ritmo", "Precisión, combo y barra de vida"),
}

@Serializable
enum class SortOrder(val label: String) {
    TITLE("Título"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
    DATE_ADDED("Añadidas recientemente"),
    RECENT("Jugadas recientemente"),
    DURATION("Duración"),
}

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    /** How game colors change during a song, unless the song has its own choice. */
    val colorFlow: ColorFlow = ColorFlow.FIXED,
    val trackStyle: TrackStyle = TrackStyle.PERSPECTIVE,
    /** 3 or 4 lanes; every song has a chart for each. */
    val lanes: Int = 4,
    /** 1..10; higher = tiles approach faster. */
    val scrollSpeed: Int = 6,
    /** Added to judgments: positive when the player hears/taps late (from calibration). */
    val inputOffsetMs: Int = 0,
    /** Shifts where tiles are drawn relative to the audio. */
    val visualOffsetMs: Int = 0,
    val vibration: Boolean = false,
    /** 0 = low, 1 = medium, 2 = high. */
    val effects: Int = 2,
    val showFps: Boolean = false,
    val earlyLate: Boolean = true,
    val noFail: Boolean = false,
    val autoPreview: Boolean = true,
    /** Demo: the game plays itself perfectly (records are not saved). */
    val autoPlay: Boolean = false,
    val minDurationSec: Int = 30,
    val folderRules: FolderRules = FolderRules(),
    /** The first-run screen that picks the music folders has been completed. */
    val folderSetupDone: Boolean = false,
    /** Folder filter of version 0.1 (exact folders), migrated into [folderRules]. */
    val excludedFolders: Set<String> = emptySet(),
    val lastMode: GameMode = GameMode.RHYTHM,
    val lastDifficulty: Difficulty = Difficulty.NORMAL,
    val sortOrder: SortOrder = SortOrder.TITLE,
    val albumShuffle: Boolean = false,
    /** When playing an album, the next song starts after a countdown. */
    val albumAutoNext: Boolean = false,
) {
    /** Time a tile takes from the far end of the track to the hit line. */
    val approachMs: Int get() = 2600 - 200 * scrollSpeed.coerceIn(1, 10)
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs.edit().putString(KEY, json.encodeToString(Settings.serializer(), next)).apply()
    }

    private fun load(): Settings {
        val raw = prefs.getString(KEY, null) ?: return Settings()
        val s = runCatching { json.decodeFromString(Settings.serializer(), raw) }.getOrDefault(Settings())
        if (s.excludedFolders.isEmpty()) return s
        var rules = s.folderRules
        for (f in s.excludedFolders) rules = rules.set(f, false)
        return s.copy(folderRules = rules, excludedFolders = emptySet())
    }

    private companion object {
        const val KEY = "settings_json"
    }
}
