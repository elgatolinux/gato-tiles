package com.gatotiles.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatotiles.data.FolderNode
import com.gatotiles.data.FolderRules

/** Visible rows of [roots] given the [expanded] node paths, with their depth. */
fun flattenFolders(roots: List<FolderNode>, expanded: Set<String>): List<Pair<FolderNode, Int>> {
    val out = ArrayList<Pair<FolderNode, Int>>()
    fun walk(n: FolderNode, depth: Int) {
        out.add(n to depth)
        if (n.path in expanded) for (c in n.children) walk(c, depth + 1)
    }
    for (r in roots) walk(r, 0)
    return out
}

/** Folder picker rows: tap a row to use / ignore it, tap the arrow to open it. */
fun LazyListScope.folderTreeItems(
    rows: List<Pair<FolderNode, Int>>,
    expanded: Set<String>,
    rules: FolderRules,
    onExpand: (String) -> Unit,
    onRules: (FolderRules) -> Unit,
) {
    items(rows, key = { it.first.path }) { (node, depth) ->
        FolderRow(node, depth, node.path in expanded, rules, onExpand = { onExpand(node.path) }, onToggle = { onRules(node.toggle(rules)) })
    }
}

@Composable
private fun FolderRow(
    node: FolderNode,
    depth: Int,
    open: Boolean,
    rules: FolderRules,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val state = node.state(rules)
    val on = state != FolderNode.State.OFF
    val arrow by animateFloatAsState(if (open) 90f else 0f, label = "arrow")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(start = (depth * 18).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (node.children.isNotEmpty()) {
                IconButton(onClick = onExpand) {
                    Icon(Icons.Rounded.ChevronRight, if (open) "Cerrar" else "Abrir", Modifier.rotate(arrow), tint = cs.onSurfaceVariant)
                }
            }
        }
        TriStateCheckbox(
            state = when (state) {
                FolderNode.State.ON -> ToggleableState.On
                FolderNode.State.OFF -> ToggleableState.Off
                FolderNode.State.PARTIAL -> ToggleableState.Indeterminate
            },
            onClick = onToggle,
        )
        val icon = when {
            depth == 0 && node.name.startsWith("Tarjeta") -> Icons.Rounded.SdCard
            depth == 0 -> Icons.Rounded.PhoneAndroid
            node.autoReason != null && !on -> Icons.Rounded.FolderOff
            else -> Icons.Rounded.Folder
        }
        Icon(icon, null, Modifier.size(22.dp), tint = if (on) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                node.name,
                style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Normal,
                color = if (on) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(if (node.total == 1) "1 audio" else "${node.total} audios")
                if (node.ignoredInside > 0) append(" · ${node.ignoredInside} ignorados")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (node.autoReason != null) {
                Surface(shape = RoundedCornerShape(50), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) {
                    Text(
                        node.autoReason,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
