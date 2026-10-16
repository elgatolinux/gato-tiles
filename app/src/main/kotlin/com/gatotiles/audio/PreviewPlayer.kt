package com.gatotiles.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.gatotiles.data.Song

/** Plays a fading song preview on the song screen (sync does not matter here). */
class PreviewPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var currentId = -1L
    private var currentVolume = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var fadeTask: Runnable? = null

    fun play(song: Song, startMs: Int) {
        if (currentId == song.id && player != null) return
        stop()
        currentId = song.id
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(context, song.uri)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (startMs > 0) it.seekTo(startMs)
                it.start()
                fade(it, 0f, 0.75f, 1200, null)
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            player = null
            currentId = -1
        }
    }

    fun stop() {
        val mp = player ?: return
        player = null
        currentId = -1
        fade(mp, currentVolume, 0f, 250) { runCatching { mp.release() } }
    }

    private fun fade(mp: MediaPlayer, from: Float, to: Float, durationMs: Long, done: (() -> Unit)?) {
        fadeTask?.let { handler.removeCallbacks(it) }
        val start = System.currentTimeMillis()
        val task = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                val v = from + (to - from) * t
                currentVolume = v
                runCatching { mp.setVolume(v, v) }
                if (t < 1f) {
                    handler.postDelayed(this, 30)
                } else {
                    fadeTask = null
                    done?.invoke()
                }
            }
        }
        fadeTask = task
        handler.post(task)
    }
}
