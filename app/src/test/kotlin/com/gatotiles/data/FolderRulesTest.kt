package com.gatotiles.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRulesTest {
    private val root = "/storage/emulated/0"
    private val music = "$root/Music/Rock"
    private val music2 = "$root/Music/Jazz"
    private val download = "$root/Download"
    private val voice = "$root/WhatsApp/Media/WhatsApp Voice Notes/202341"
    private val sd = "/storage/1234-ABCD/Musica"

    private fun auto(path: String) = FolderClassifier.classify(path, listOf("song.mp3" to AudioKind.MUSIC))

    private val folders = listOf(music, music2, download, voice, sd).map { FolderInfo(it, 10, auto(it)) }

    private fun included(rules: FolderRules) = folders.filter { rules.isIncluded(it.path, it.auto?.root) }.map { it.path }.toSet()

    @Test
    fun classifierIgnoresMessagingRecordingsAndSystemSounds() {
        assertEquals("Audios de WhatsApp", auto(voice)?.reason)
        assertEquals("$root/WhatsApp", auto(voice)?.root)
        assertEquals("Audios de Telegram", auto("$root/Telegram/Telegram Audio")?.reason)
        assertEquals("Grabaciones y notas de voz", auto("$root/Recordings")?.reason)
        assertEquals("Grabaciones y notas de voz", auto("$root/MIUI/sound_recorder/call_rec")?.reason)
        assertEquals("Tonos del sistema", auto("$root/Ringtones")?.reason)
        assertEquals("Audios de WhatsApp", auto("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio")?.reason)
        assertNotNull(auto("$root/Android/data/com.some.game/files/sfx"))
        assertNull(auto(music))
        assertNull(auto(download))
        assertNull(auto(sd))
        // Voice notes recognised by their file names in an ordinary folder.
        val notes = List(6) { "PTT-20240101-WA00$it.opus" to AudioKind.MUSIC } + ("song.mp3" to AudioKind.MUSIC)
        assertNotNull(FolderClassifier.classify("$root/Download/Varios", notes))
        assertNotNull(FolderClassifier.classify("$root/Varios", List(3) { "ep$it.mp3" to AudioKind.PODCAST }))
    }

    @Test
    fun automaticUsesEverythingButIgnoredFolders() {
        assertEquals(setOf(music, music2, download, sd), included(FolderRules()))
    }

    @Test
    fun deepestRuleWinsAndParentsDoNotPullVoiceNotes() {
        var r = FolderRules().set(root, false)
        assertEquals(setOf(sd), included(r))
        r = r.set("$root/Music", true)
        assertEquals(setOf(music, music2, sd), included(r))
        r = r.set(music2, false)
        assertEquals(setOf(music, sd), included(r))
        // Ticking the whole storage again resets the rules inside it but keeps voice notes out.
        r = r.set(root, true)
        assertEquals(setOf(music, music2, download, sd), included(r))
        // Only a rule on the WhatsApp folder itself brings them in.
        r = r.set("$root/WhatsApp", true)
        assertTrue(voice in included(r))
    }

    @Test
    fun coversOnlyWholeSegments() {
        assertTrue(FolderRules.covers("$root/Music", "$root/Music/Rock"))
        assertTrue(FolderRules.covers("$root/Music", "$root/Music"))
        assertFalse(FolderRules.covers("$root/Music", "$root/Musicals"))
    }

    @Test
    fun treeTogglesFollowTheVisibleState() {
        val tree = FolderNode.build(folders)
        assertEquals(listOf("Tarjeta SD", "Memoria interna"), tree.map { it.name }.sortedDescending())
        val internal = tree.first { it.path == root }
        assertEquals(FolderNode.State.ON, internal.state(FolderRules()))
        // "Music" holds two folders, WhatsApp collapses into one chain row.
        val names = internal.children.map { it.name }
        assertTrue(names.toString(), "Music" in names && names.any { it.startsWith("WhatsApp › Media") })
        val wa = internal.children.first { it.name.startsWith("WhatsApp") }
        assertEquals("Audios de WhatsApp", wa.autoReason)
        assertEquals(FolderNode.State.OFF, wa.state(FolderRules()))
        var r = wa.toggle(FolderRules())
        assertEquals(FolderNode.State.ON, wa.state(r))
        assertEquals(FolderNode.State.ON, internal.state(r))
        r = internal.toggle(r)
        assertEquals(FolderNode.State.OFF, internal.state(r))
        assertEquals(setOf(sd), included(r))
        val musicNode = internal.children.first { it.name == "Music" }
        r = musicNode.toggle(r)
        assertEquals(FolderNode.State.PARTIAL, internal.state(r))
        assertEquals(setOf(music, music2, sd), included(r))
    }

    @Test
    fun nodeAboveIgnoredFoldersCanStillIncludeThem() {
        // Every folder under "Android" is ignored automatically, deeper than the node itself.
        val list = listOf("$root/Android/media/com.whatsapp/WhatsApp/Media/Notes", "$root/Music").map { FolderInfo(it, 3, auto(it)) }
        val internal = FolderNode.build(list).single()
        val android = internal.children.first { it.name.startsWith("Android") }
        assertEquals(FolderNode.State.OFF, android.state(FolderRules()))
        val r = android.toggle(FolderRules())
        assertEquals(FolderNode.State.ON, android.state(r))
    }
}
