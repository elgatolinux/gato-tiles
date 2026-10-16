package com.gatotiles.data

/**
 * Folder tree for the folder picker: one root per storage volume, and chains of folders that
 * only contain one subfolder are shown as a single row ("Music › Rock").
 */
class FolderNode(
    val path: String,
    val name: String,
    val children: List<FolderNode>,
    /** Folders with audio at or below this node. */
    val leaves: List<FolderInfo>,
) {
    val total: Int = leaves.sumOf { it.count }

    /**
     * The folders whose state this node shows: automatically ignored folders only count for
     * nodes inside them (a ticked "Internal storage" does not mean voice notes are used).
     */
    val relevant: List<FolderInfo> = leaves.filter { it.auto == null || FolderRules.covers(it.auto.root, path) }.ifEmpty { leaves }

    /** Reason shown when every folder under this node is ignored automatically for the same reason. */
    val autoReason: String? = leaves.map { it.auto?.reason }.distinct().singleOrNull()

    val ignoredInside: Int = if (autoReason != null) 0 else leaves.filter { it.auto != null }.sumOf { it.count }

    enum class State { ON, OFF, PARTIAL }

    fun state(rules: FolderRules): State {
        val inc = relevant.count { rules.isIncluded(it.path, it.auto?.root) }
        return when (inc) {
            0 -> State.OFF
            relevant.size -> State.ON
            else -> State.PARTIAL
        }
    }

    /** Rules after tapping this node: everything in it on (or off when it was fully on). */
    fun toggle(rules: FolderRules): FolderRules {
        val include = state(rules) != State.ON
        var r = rules.set(path, include)
        if (include) {
            for (leaf in relevant) {
                val root = leaf.auto?.root ?: continue
                if (!FolderRules.covers(root, path)) r = r.set(root, true)
            }
        }
        return r
    }

    companion object {
        private val volumeRegex = Regex("^(/storage/emulated/\\d+|/storage/[^/]+|/sdcard|/mnt/[^/]+/[^/]+)")

        fun volumeName(path: String): String = when {
            path == "/storage/emulated/0" || path == "/sdcard" -> "Memoria interna"
            path.startsWith("/storage/emulated/") -> "Memoria interna (usuario ${path.substringAfterLast('/')})"
            path.startsWith("/storage/") -> "Tarjeta SD"
            else -> path
        }

        fun build(folders: List<FolderInfo>): List<FolderNode> {
            val byVolume = folders.groupBy { volumeRegex.find(it.path)?.value ?: "/" + it.path.trim('/').substringBefore('/') }
            return byVolume.entries.sortedBy { it.key }.map { (vol, list) -> buildNode(vol, volumeName(vol), list, keepSelf = true) }
        }

        private fun buildNode(path: String, name: String, folders: List<FolderInfo>, keepSelf: Boolean): FolderNode {
            var p = path
            var label = name
            // Collapse chains of folders that only hold one subfolder and no audio of their own.
            while (!keepSelf) {
                val own = folders.any { it.path == p }
                val next = folders.mapNotNull { childSegment(p, it.path) }.distinct()
                if (own || next.size != 1) break
                p = "$p/${next[0]}"
                label = "$label › ${next[0]}"
            }
            val childNames = folders.mapNotNull { childSegment(p, it.path) }.distinct().sortedBy { it.lowercase() }
            val children = childNames.map { seg ->
                val cp = "$p/$seg"
                buildNode(cp, seg, folders.filter { FolderRules.covers(cp, it.path) }, keepSelf = false)
            }
            return FolderNode(p, label, children, folders)
        }

        /** First path segment of [folder] below [parent], or null when it is not below it. */
        private fun childSegment(parent: String, folder: String): String? {
            if (!FolderRules.covers(parent, folder) || folder == parent) return null
            return folder.substring(parent.length + 1).substringBefore('/')
        }
    }
}
