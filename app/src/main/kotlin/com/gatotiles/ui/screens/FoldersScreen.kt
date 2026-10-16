package com.gatotiles.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatotiles.GatoApp
import com.gatotiles.data.FolderInfo
import com.gatotiles.data.FolderNode
import com.gatotiles.data.FolderRules
import com.gatotiles.ui.components.flattenFolders
import com.gatotiles.ui.components.folderTreeItems

/** State shared by the first-run screen and the folder settings. */
class FolderPickerState(initialCustom: Boolean, initialRules: FolderRules) {
    var custom by mutableStateOf(initialCustom)
    var rules by mutableStateOf(initialRules)
    var expanded by mutableStateOf(emptySet<String>())

    /** Rules to save: automatic mode ignores whatever was ticked. */
    val effectiveRules: FolderRules get() = if (custom) rules else FolderRules()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onBack: () -> Unit) {
    val app = GatoApp.app
    val s by app.settings.state.collectAsStateWithLifecycle()
    val folders by app.music.folders.collectAsStateWithLifecycle()
    val picker = remember { FolderPickerState(!s.folderRules.isAutomatic, s.folderRules) }
    fun save() {
        val r = picker.effectiveRules
        app.settings.update { it.copy(folderRules = r) }
        app.music.applyRules(r)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Carpetas de música") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
        ) {
            folderPicker(picker, folders, onChange = ::save)
        }
    }
}

/** Mode cards, summary and (in custom mode) the folder tree, as LazyColumn items. */
fun LazyListScope.folderPicker(picker: FolderPickerState, folders: List<FolderInfo>, onChange: () -> Unit) {
    item(key = "mode-auto") {
        ModeCard(
            icon = Icons.Rounded.AutoAwesome,
            title = "Automático",
            text = "Todas las carpetas con música. Se ignoran notas de voz, grabaciones, tonos y audios de WhatsApp o Telegram.",
            selected = !picker.custom,
            badge = "Recomendado",
        ) {
            picker.custom = false
            onChange()
        }
    }
    item(key = "mode-custom") {
        ModeCard(
            icon = Icons.Rounded.Checklist,
            title = "Elegir carpetas",
            text = "Marca solo las carpetas que quieres usar en el juego. Las carpetas nuevas dentro de ellas se añaden solas.",
            selected = picker.custom,
        ) {
            if (!picker.custom) {
                picker.custom = true
                // Start from the automatic choice and open the storage roots.
                picker.expanded = FolderNode.build(folders).map { it.path }.toSet()
                onChange()
            }
        }
    }
    val rules = picker.effectiveRules
    val total = folders.sumOf { it.count }
    val used = folders.sumOf { if (rules.isIncluded(it.path, it.auto?.root)) it.count else 0 }
    item(key = "summary") {
        Text(
            if (total == 0) "No encontré audios en el teléfono." else "Se usarán $used de $total audios",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        )
    }
    if (!picker.custom) {
        item(key = "auto-summary") { AutoSummary(folders) }
        return
    }
    val roots = FolderNode.build(folders)
    item(key = "quick") {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {
                picker.rules = FolderRules()
                onChange()
            }, label = { Text("Recomendadas") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) })
            AssistChip(onClick = {
                picker.rules = roots.fold(FolderRules()) { r, n -> r.set(n.path, false) }
                onChange()
            }, label = { Text("Ninguna") }, leadingIcon = { Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp)) })
        }
    }
    folderTreeItems(
        rows = flattenFolders(roots, picker.expanded),
        expanded = picker.expanded,
        rules = picker.rules,
        onExpand = { p -> picker.expanded = if (p in picker.expanded) picker.expanded - p else picker.expanded + p },
        onRules = {
            picker.rules = it
            onChange()
        },
    )
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, text: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, label = "modeCard")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = bg,
        border = if (selected) BorderStroke(2.dp, cs.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(26.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(50), color = cs.primary, contentColor = cs.onPrimary) {
                            Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) cs.primary else cs.outline,
            )
        }
    }
}

/** What automatic mode uses and ignores, biggest folders first. */
@Composable
private fun AutoSummary(folders: List<FolderInfo>) {
    val cs = MaterialTheme.colorScheme
    val used = folders.filter { it.auto == null }.sortedByDescending { it.count }
    val ignored = folders.filter { it.auto != null }.groupBy { it.auto!!.reason }.mapValues { e -> e.value.sumOf { it.count } }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in used.take(6)) {
            SummaryLine(Icons.Rounded.Folder, shortName(f.path), "${f.count}", cs.primary)
        }
        if (used.size > 6) {
            Text("y ${used.size - 6} carpetas más", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        for ((reason, n) in ignored) {
            SummaryLine(Icons.Rounded.VisibilityOff, "$reason · ignorados", "$n", cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryLine(icon: ImageVector, text: String, count: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Music › Rock" style name from the last two folders of a path. */
fun shortName(path: String): String {
    val segs = path.trim('/').split('/')
    val tail = segs.takeLast(2)
    return if (segs.size >= 4 && segs.takeLast(2).first() != "0") tail.joinToString(" › ") else segs.last()
}
