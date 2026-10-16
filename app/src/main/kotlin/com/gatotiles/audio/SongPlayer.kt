package com.gatotiles.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Plays a song through AudioTrack and exposes a precise song clock.
 *
 * A lead-in of silence is written before the music, so the clock simply counts output
 * frames: song time = (frames heard - lead-in frames) / sample rate. "Frames heard" comes
 * from AudioTrack.getTimestamp(), which already includes the output latency, extrapolated
 * with System.nanoTime() between polls.
 */
class SongPlayer(private val context: Context, private val uri: Uri) {
    private val clockLock = Any()
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var leadInFrames = 0L
    @Volatile private var leadInMsRequested = 0
    @Volatile private var endFrame = Long.MAX_VALUE

    @Volatile var sampleRate = 44100
        private set
    @Volatile var started = false
        private set
    @Volatile var finished = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var volume = 1f

    val spectrum = SpectrumTap()

    private var clockRunning = false
    private var baseNanos = 0L
    private var baseFrames = 0.0
    private var frozenFrames = 0.0
    private var synced = false
    private var lastPoll = 0L
    private val ts = AudioTimestamp()

    val isPaused: Boolean get() = paused

    fun start(leadInMs: Int) {
        check(thread == null) { "already started" }
        leadInMsRequested = leadInMs
        stopRequested = false
        thread = Thread({ runDecoder(leadInMs) }, "song-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runDecoder(leadInMs: Int) {
        var dec: PcmDecoder? = null
        try {
            dec = PcmDecoder(context, uri)
            val inBuf = FloatArray(4096 * 8)
            var frames = 0
            while (frames == 0 && !stopRequested) frames = dec.read(inBuf)
            if (frames <= 0 || stopRequested) {
                finished = true
                return
            }
            val sr = dec.sampleRate
            val ch = dec.channels
            sampleRate = sr
            val outCh = if (ch >= 2) 2 else 1
            val mask = if (outCh == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            spectrum.reset(sr, BANDS)
            val minBuf = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf * 2, sr * outCh * 4 / 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            leadInFrames = sr.toLong() * leadInMs / 1000
            t.play()
            synchronized(clockLock) {
                clockRunning = !paused
                baseNanos = System.nanoTime()
                baseFrames = 0.0
                frozenFrames = 0.0
                synced = false
                lastPoll = 0L
            }
            if (paused) t.pause()
            started = true

            var out = FloatArray(inBuf.size)
            var mono = FloatArray(inBuf.size)
            var written = 0L
            var remaining = leadInFrames
            val silenceFrames = 2048
            while (remaining > 0 && !stopRequested) {
                val n = min(remaining, silenceFrames.toLong()).toInt()
                java.util.Arrays.fill(out, 0, n * outCh, 0f)
                java.util.Arrays.fill(mono, 0, n, 0f)
                spectrum.push(mono, n, written)
                writeFully(t, out, n * outCh)
                written += n
                remaining -= n
            }
            while (!stopRequested) {
                if (out.size < frames * outCh) out = FloatArray(frames * outCh)
                if (mono.size < frames) mono = FloatArray(frames)
                val vol = volume
                when (ch) {
                    1 -> for (i in 0 until frames) {
                        val v = inBuf[i]
                        out[i] = v * vol
                        mono[i] = v
                    }
                    2 -> for (i in 0 until frames) {
                        val l = inBuf[2 * i]
                        val r = inBuf[2 * i + 1]
                        out[2 * i] = l * vol
                        out[2 * i + 1] = r * vol
                        mono[i] = (l + r) * 0.5f
                    }
                    else -> for (i in 0 until frames) {
                        var l = 0f
                        var r = 0f
                        for (c in 0 until ch) if (c % 2 == 0) l += inBuf[i * ch + c] else r += inBuf[i * ch + c]
                        l /= (ch + 1) / 2
                        r /= ch / 2
                        out[2 * i] = l * vol
                        out[2 * i + 1] = r * vol
                        mono[i] = (l + r) * 0.5f
                    }
                }
                spectrum.push(mono, frames, written)
                writeFully(t, out, frames * outCh)
                written += frames
                frames = dec.read(inBuf)
                if (frames < 0) break
            }
            endFrame = written
            while (!stopRequested) {
                if (currentFrames(System.nanoTime()) >= endFrame) break
                Thread.sleep(15)
            }
            if (!stopRequested) finished = true
        } catch (e: Throwable) {
            if (!stopRequested) error = e.message ?: e.javaClass.simpleName
            finished = true
        } finally {
            dec?.close()
        }
    }

    private fun writeFully(t: AudioTrack, data: FloatArray, len: Int) {
        var off = 0
        while (off < len && !stopRequested) {
            val w = t.write(data, off, len - off, AudioTrack.WRITE_BLOCKING)
            if (w < 0) throw IllegalStateException("Error de audio ($w)")
            if (w == 0) Thread.sleep(2)
            off += w
        }
    }

    /** Output frame currently reaching the speaker (lead-in included). */
    fun currentFrames(now: Long): Double = synchronized(clockLock) {
        if (!clockRunning) return frozenFrames
        poll(now)
        baseFrames + (now - baseNanos) * sampleRate / 1e9
    }

    private fun poll(now: Long) {
        val t = track ?: return
        val interval = if (synced) 250_000_000L else 15_000_000L
        if (now - lastPoll < interval) return
        lastPoll = now
        if (t.getTimestamp(ts) && ts.framePosition > 0) {
            val predicted = baseFrames + (ts.nanoTime - baseNanos) * sampleRate / 1e9
            val err = ts.framePosition - predicted
            baseNanos = ts.nanoTime
            if (!synced || abs(err) > sampleRate * 0.03) {
                baseFrames = ts.framePosition.toDouble()
                synced = true
            } else {
                baseFrames = predicted + err * 0.2
            }
        }
    }

    /** Song time in ms at System.nanoTime() [now]; negative during the lead-in. */
    fun songTimeMs(now: Long = System.nanoTime()): Double {
        if (!started) return -leadInMsRequested.toDouble()
        return (currentFrames(now) - leadInFrames) * 1000.0 / sampleRate
    }

    /** Output frame for a song time, for the spectrum tap. */
    fun frameForSongTime(ms: Double): Long = (ms * sampleRate / 1000.0).toLong() + leadInFrames

    fun pause() {
        if (paused) return
        paused = true
        val now = System.nanoTime()
        synchronized(clockLock) {
            if (clockRunning) {
                poll(now)
                frozenFrames = baseFrames + (now - baseNanos) * sampleRate / 1e9
            }
            clockRunning = false
        }
        runCatching { track?.pause() }
    }

    fun resume() {
        if (!paused) return
        paused = false
        val t = track
        if (t != null) {
            runCatching { t.play() }
            synchronized(clockLock) {
                baseNanos = System.nanoTime()
                baseFrames = frozenFrames
                clockRunning = true
                synced = false
                lastPoll = 0L
            }
        }
    }

    fun release() {
        stopRequested = true
        val t = track
        runCatching { t?.pause() }
        runCatching { t?.flush() }
        thread?.join(800)
        runCatching { t?.stop() }
        runCatching { t?.release() }
        track = null
    }

    companion object {
        const val BANDS = 24
    }
}
