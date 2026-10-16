package com.gatotiles.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gatotiles.R
import com.gatotiles.ui.audioPermission

@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted() else denied = true
    }
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background, cs.background)))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(132.dp).clip(RoundedCornerShape(36.dp))) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Text("Gato Tiles", style = MaterialTheme.typography.displaySmall)
            Text(
                "Juega con tus propias canciones. Para crear las pistas necesito leer la música " +
                    "guardada en tu teléfono; nada sale del dispositivo.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { launcher.launch(audioPermission()) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Permitir acceso a la música")
            }
            if (denied) {
                Text(
                    "Sin este permiso no puedo ver tus canciones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Abrir ajustes de la app") }
            }
        }
    }
}
