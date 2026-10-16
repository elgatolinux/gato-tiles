package com.gatotiles.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Pull-style audio decoder (MediaExtractor + MediaCodec) that yields interleaved float PCM.
 * Handles every format the device supports natively: MP3, FLAC, AAC/M4A, OGG Vorbis,
 * Opus, WAV and more. [sampleRate] and [channels] are final after the first [read].
 */
class PcmDecoder(context: Context, uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending = FloatArray(0)
    private var pendingPos = 0
    private var pendingLen = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var released = false

    var sampleRate: Int = 44100
        private set
    var channels: Int = 2
        private set
    val durationUs: Long
    val mime: String

    /** Presentation time of the last decoded buffer. */
    var positionUs: Long = 0
        private set

    init {
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("El archivo no tiene pista de audio")
        }
        extractor.selectTrack(track)
        mime = format.getString(MediaFormat.KEY_MIME)!!
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            throw IllegalArgumentException("Formato no soportado por el teléfono: $mime")
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        pendingPos = 0
        pendingLen = 0
    }

    /**
     * Fills [out] with up to out.size / channels frames of interleaved floats.
     * Returns the number of frames, or -1 at the end of the stream.
     */
    fun read(out: FloatArray): Int {
        while (true) {
            if (pendingLen > pendingPos) {
                val ch = channels
                val frames = minOf((pendingLen - pendingPos) / ch, out.size / ch)
                System.arraycopy(pending, pendingPos, out, 0, frames * ch)
                pendingPos += frames * ch
                return frames
            }
            if (outputDone) return -1
            // Keep the codec saturated with input; waiting on output with input starved
            // would cost a full timeout per buffer.
            while (!inputDone && feedInput()) Unit
            val idx = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 1_000)
            when {
                idx >= 0 -> {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(idx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        convert(buf.slice().order(ByteOrder.nativeOrder()))
                        positionUs = info.presentationTimeUs
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
            }
        }
    }

    /** Queues one input buffer if the codec has a free one; returns whether it did. */
    private fun feedInput(): Boolean {
        if (inputDone) return false
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) return false
        val buf = codec.getInputBuffer(idx)!!
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
        return true
    }

    private fun ensurePending(n: Int) {
        if (pending.size < n) pending = FloatArray(n)
        pendingPos = 0
        pendingLen = n
    }

    private fun convert(b: java.nio.ByteBuffer) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = b.asFloatBuffer()
                ensurePending(fb.remaining())
                fb.get(pending, 0, pendingLen)
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = b.remaining()
                ensurePending(n)
                for (i in 0 until n) pending[i] = ((b.get().toInt() and 0xFF) - 128) / 128f
            }
            ENCODING_PCM_24BIT_PACKED -> {
                val n = b.remaining() / 3
                ensurePending(n)
                for (i in 0 until n) {
                    val lo = b.get().toInt() and 0xFF
                    val mid = b.get().toInt() and 0xFF
                    val hi = b.get().toInt()
                    pending[i] = ((hi shl 16) or (mid shl 8) or lo) / 8388608f
                }
            }
            ENCODING_PCM_32BIT -> {
                val ib = b.asIntBuffer()
                val n = ib.remaining()
                ensurePending(n)
                for (i in 0 until n) pending[i] = ib.get() / 2147483648f
            }
            else -> {
                val sb = b.asShortBuffer()
                val n = sb.remaining()
                ensurePending(n)
                for (i in 0 until n) pending[i] = sb.get() / 32768f
            }
        }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}
