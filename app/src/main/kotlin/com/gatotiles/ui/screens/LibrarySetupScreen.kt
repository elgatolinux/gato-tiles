package com.gatotiles.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.R

/** First run: choose where the music is (automatic or hand-picked folders). */
@Composable
fun LibrarySetupScreen(onDone: () -> Unit) {
    val app = GatoApp.app
    val s by app.settings.state.collectAsStateWithLifecycle()
    val folders by app.music.folders.collectAsStateWithLifecycle()
    val scanning by app.music.scanning.collectAsStateWithLifecycle()
    val scanned by app.music.scannedOnce.collectAsStateWithLifecycle()
    val picker = remember { FolderPickerState(!s.folderRules.isAutomatic, s.folderRules) }
    val cs = MaterialTheme.colorScheme
    val rules = picker.effectiveRules
    val used = folders.sumOf { if (rules.isIncluded(it.path, it.auto?.root)) it.count else 0 }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to cs.primaryContainer.copy(alpha = 0.6f), 0.4f to cs.background, 1f to cs.background)),
    ) {
        if (!scanned || (scanning && folders.isEmpty())) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text("Buscando música en tu teléfono…", color = cs.onSurfaceVariant)
            }
            return@Box
        }
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 132.dp),
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("¿Dónde está tu música?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Encontré ${app.music.totalFound} audios en ${folders.size} carpetas. Elige cuáles quieres usar para jugar; " +
                            "puedes cambiarlo cuando quieras en Ajustes → Carpetas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            folderPicker(picker, folders, onChange = {})
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, cs.background.copy(alpha = 0.94f), cs.background)))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        ) {
            Button(
                onClick = {
                    val r = picker.effectiveRules
                    app.settings.update { it.copy(folderRules = r, folderSetupDone = true) }
                    app.music.applyRules(r)
                    app.charts.refreshCachedKeys(app.music.songs.value)
                    onDone()
                },
                enabled = used > 0 || folders.isEmpty(),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Text(
                    when {
                        folders.isEmpty() -> "Continuar"
                        used == 1 -> "Empezar con 1 canción"
                        else -> "Empezar con $used canciones"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
