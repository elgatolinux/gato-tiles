package com.gatotiles.data

import kotlinx.serialization.Serializable

/**
 * Which folders the library uses. Folders without a rule follow the automatic choice (every
 * folder with music except voice notes, recordings, ringtones...). A rule applies to its
 * folder and everything below it; the deepest rule wins. Automatically ignored folders only
 * come back with a rule on themselves (or inside them), so ticking a parent folder does not
 * pull in voice notes.
 */
@Serializable
data class FolderRules(
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
) {
    val isAutomatic: Boolean get() = include.isEmpty() && exclude.isEmpty()

    /** [autoRoot] = where the automatic exclusion of [folder] starts, or null when it is music. */
    fun isIncluded(folder: String, autoRoot: String?): Boolean {
        var best: String? = null
        var bestInclude = true
        for (p in include) if (covers(p, folder) && (best == null || p.length > best.length)) {
            best = p
            bestInclude = true
        }
        for (p in exclude) if (covers(p, folder) && (best == null || p.length >= best.length)) {
            best = p
            bestInclude = false
        }
        if (autoRoot != null) return best != null && covers(autoRoot, best) && bestInclude
        return best == null || bestInclude
    }

    /** Sets [path] (and everything below it) to [included], dropping the rules inside it. */
    fun set(path: String, included: Boolean): FolderRules {
        val inc = include.filterNot { covers(path, it) }.toMutableSet()
        val exc = exclude.filterNot { covers(path, it) }.toMutableSet()
        if (included) inc.add(path) else exc.add(path)
        return FolderRules(inc, exc)
    }

    companion object {
        /** True when [path] is [folder] or one of its parents. */
        fun covers(path: String, folder: String): Boolean =
            folder == path || (folder.length > path.length && folder.startsWith(path) && (path.endsWith("/") || folder[path.length] == '/'))
    }
}

/** Why a folder is not used automatically. */
data class AutoExclusion(val root: String, val reason: String)

object FolderClassifier {
    private val voiceFile = Regex(
        "^(ptt-|aud-\\d{8}-wa|voice[ _-]?\\d|rec[_-]?\\d|recording|grabaci[oó]n|nota de voz|audio_\\d{4}-)",
        RegexOption.IGNORE_CASE,
    )

    private fun reasonForSegment(seg: String): String? {
        val s = seg.lowercase()
        return when {
            "whatsapp" in s -> "Audios de WhatsApp"
            "telegram" in s -> "Audios de Telegram"
            s in setOf("signal", "viber", "messenger", "discord", "snapchat", "instagram", "wechat", "line") -> "Audios de mensajería"
            "record" in s || s.startsWith("grabac") || s in setOf("voice", "voices", "voice notes", "voicenotes", "notas de voz", "call", "calls", "llamadas", "sound_recorder") ->
                "Grabaciones y notas de voz"
            s in setOf("notifications", "ringtones", "alarms", "notificaciones", "tonos", "alarmas", "ui") -> "Tonos del sistema"
            "podcast" in s -> "Podcasts"
            "audiobook" in s || "audiolibro" in s -> "Audiolibros"
            else -> null
        }
    }

    /**
     * [folder] = absolute folder path; [files] = (file name, kind) of its audio files. Looks
     * at the path first (messaging apps, recorders, system sounds), then at the files.
     */
    fun classify(folder: String, files: List<Pair<String, AudioKind>>): AutoExclusion? {
        val segs = folder.split('/')
        var prefix = ""
        var androidAt = -1
        for ((i, seg) in segs.withIndex()) {
            if (seg.isEmpty()) continue
            prefix = "$prefix/$seg"
            if (seg.equals("Android", true)) androidAt = i
            if (androidAt >= 0 && i == androidAt + 1 && (seg.equals("data", true) || seg.equals("media", true) || seg.equals("obb", true))) {
                val app = segs.getOrNull(i + 1)?.lowercase() ?: ""
                if ("whatsapp" in app) return AutoExclusion("$prefix/${segs[i + 1]}", "Audios de WhatsApp")
                if ("telegram" in app) return AutoExclusion("$prefix/${segs[i + 1]}", "Audios de Telegram")
                return AutoExclusion(prefix, "Archivos de otras apps")
            }
            reasonForSegment(seg)?.let { return AutoExclusion(prefix, it) }
        }
        if (files.isEmpty()) return null
        val flagged = files.count { (name, kind) -> kind != AudioKind.MUSIC || voiceFile.containsMatchIn(name) }
        if (flagged * 2 > files.size) {
            val podcasts = files.count { it.second == AudioKind.PODCAST }
            val books = files.count { it.second == AudioKind.AUDIOBOOK }
            val reason = when {
                podcasts * 2 > files.size -> "Podcasts"
                books * 2 > files.size -> "Audiolibros"
                else -> "Grabaciones y notas de voz"
            }
            return AutoExclusion(folder, reason)
        }
        return null
    }
}
