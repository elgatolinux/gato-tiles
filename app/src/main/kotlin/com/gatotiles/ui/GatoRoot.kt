package com.gatotiles.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gatotiles.GatoApp
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.data.FolderRules
import com.gatotiles.data.GameMode
import com.gatotiles.ui.screens.AlbumAddSongsScreen
import com.gatotiles.ui.screens.AlbumRunScreen
import com.gatotiles.ui.screens.AlbumScreen
import com.gatotiles.ui.screens.CalibrationScreen
import com.gatotiles.ui.screens.FoldersScreen
import com.gatotiles.ui.screens.GameScreen
import com.gatotiles.ui.screens.LibraryScreen
import com.gatotiles.ui.screens.LibrarySetupScreen
import com.gatotiles.ui.screens.PermissionScreen
import com.gatotiles.ui.screens.SettingsScreen
import com.gatotiles.ui.screens.SongScreen
import com.gatotiles.ui.theme.GatoTheme
import com.gatotiles.ui.theme.isDarkTheme
import com.gatotiles.util.ArtColors
import kotlinx.coroutines.flow.MutableStateFlow

/** Seed color of the whole UI: follows the cover of the song the user is looking at. */
object ThemeSeed {
    val seed = MutableStateFlow(ArtColors.DEFAULT_SEED)
}

fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun GatoRoot() {
    val app = GatoApp.app
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val seed by ThemeSeed.seed.collectAsStateWithLifecycle()
    val effectiveSeed = if (settings.dynamicColor) seed else ArtColors.DEFAULT_SEED
    GatoTheme(effectiveSeed, settings.themeMode) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            AppContent(settings.minDurationSec, settings.folderRules, settings.folderSetupDone, isDarkTheme(settings.themeMode))
        }
    }
}

@Composable
private fun AppContent(minDurationSec: Int, folderRules: FolderRules, setupDone: Boolean, dark: Boolean) {
    val app = GatoApp.app
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED)
    }
    if (!granted) {
        PermissionScreen(onGranted = { granted = true })
        return
    }
    LaunchedEffect(Unit) {
        if (!app.music.scannedOnce.value) {
            app.music.scan(minDurationSec * 1000L, folderRules)
            app.charts.refreshCachedKeys(app.music.songs.value)
        }
    }
    if (!setupDone) {
        LibrarySetupScreen(onDone = {})
        return
    }
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = "library",
        enterTransition = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) { it / 6 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) { it / 6 } },
    ) {
        composable("library") {
            LibraryScreen(
                onOpenSong = { nav.navigate("song/${it.id}") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenAlbum = { nav.navigate("album/$it") },
            )
        }
        composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            AlbumScreen(
                albumId = id,
                onBack = { nav.popBackStack() },
                onOpenSong = { nav.navigate("song/${it.id}") },
                onAddSongs = { nav.navigate("album/$id/add") },
                onStartRun = { nav.navigate("run") },
            )
        }
        composable("album/{id}/add", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AlbumAddSongsScreen(albumId = entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
        }
        composable("run") { AlbumRunScreen(onExit = { nav.popBackStack() }) }
        composable("song/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            SongScreen(
                songId = id,
                onBack = { nav.popBackStack() },
                onPlay = { d, m -> nav.navigate("game/$id/${d.name}/${m.name}") },
            )
        }
        composable(
            "game/{id}/{diff}/{mode}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("diff") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            val a = entry.arguments!!
            GameScreen(
                songId = a.getLong("id"),
                difficulty = Difficulty.valueOf(a.getString("diff")!!),
                mode = GameMode.valueOf(a.getString("mode")!!),
                onExit = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onCalibrate = { nav.navigate("calibration") },
                onFolders = { nav.navigate("folders") },
            )
        }
        composable("calibration") { CalibrationScreen(onBack = { nav.popBackStack() }) }
        composable("folders") { FoldersScreen(onBack = { nav.popBackStack() }) }
    }
}
