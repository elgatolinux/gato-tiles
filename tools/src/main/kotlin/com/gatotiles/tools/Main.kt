package com.gatotiles.tools

import com.gatotiles.analyzer.AnalysisResult
import com.gatotiles.analyzer.SongAnalyzer
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.Note
import com.gatotiles.analyzer.rhythm.OnsetDetector
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Desktop helper to try the chart generator on real songs:
 *   analyze <file|dir> [--out dir]                → chart JSON + summary
 *   preview <file> [--diff HARD] [--out dir]      → song mixed with a click on every tile (WAV)
 *                                                   and a falling-tiles video (MP4)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val cmd = args[0]
    val rest = args.drop(1)
    val out = File(option(rest, "--out") ?: "salida").apply { mkdirs() }
    val positional = rest.filterIndexed { i, a -> !a.startsWith("--") && (i == 0 || !rest[i - 1].startsWith("--")) }
    if (positional.isEmpty()) usage()
    val target = File(positional[0])
    when (cmd) {
        "analyze" -> {
            val files = if (target.isDirectory) {
                target.listFiles()!!.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }.sortedBy { it.name }
            } else {
                listOf(target)
            }
            for (f in files) analyzeFile(f, out)
        }
        "dump" -> {
            val pcm = decode(target)
            dumpFile(target, analyze(pcm), out)
        }
        "debug" -> {
            val files = if (target.isDirectory) {
                target.listFiles()!!.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }.sortedBy { it.name }
            } else {
                listOf(target)
            }
            for (f in files) debugFile(f)
        }
        "preview" -> {
            val diff = Difficulty.valueOf((option(rest, "--diff") ?: "HARD").uppercase())
            val lanes = option(rest, "--lanes")?.toInt() ?: 4
            val pcm = decode(target)
            val result = analyze(pcm)
            writeChart(target, result, out)
            printSummary(target, result, pcm.seconds())
            val base = File(out, target.nameWithoutExtension + "." + diff.name.lowercase() + if (lanes != 4) ".${lanes}carriles" else "")
            val notes = result.chart.chart(diff, lanes).notes
            val wav = File(base.path + ".clicks.wav")
            writeClickMix(pcm, notes, wav)
            println("WAV con clics: ${wav.path}")
            if ("--no-video" !in rest) {
                val mp4 = File(base.path + ".mp4")
                renderVideo(result, diff, lanes, wav, mp4)
                println("Video: ${mp4.path}")
            }
        }
        else -> usage()
    }
}

private val AUDIO_EXT = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "ape", "alac")

/** CSV dumps of the analysis (onsets, beats, melody, energy, charts) for offline inspection. */
private fun dumpFile(file: File, r: AnalysisResult, out: File) {
    val dir = File(out, file.nameWithoutExtension + ".dump").apply { mkdirs() }
    File(dir, "onsets.csv").printWriter().use { w ->
        w.println("t,strength,low,mid,high,persistence")
        for (o in r.onsets) w.println("%.4f,%.4f,%.4f,%.4f,%.4f,%.4f".format(o.time, o.strength, o.low, o.mid, o.high, o.persistence))
    }
    File(dir, "envelope.csv").printWriter().use { w ->
        val env = OnsetDetector.envelopes(r.features)
        w.println("t,total,low,mid,high,rmsDb")
        for (i in env.total.indices) {
            w.println("%.4f,%.4f,%.4f,%.4f,%.4f,%.1f".format(r.features.frameTime(i.toDouble()), env.total[i], env.low[i], env.mid[i], env.high[i], 20 * log10(max(r.features.rms[i], 1e-9f))))
        }
    }
    File(dir, "beats.csv").printWriter().use { w ->
        w.println("i,t,downbeat")
        r.grid.beats.forEachIndexed { i, t -> w.println("%d,%.4f,%d".format(i, t, if (r.grid.isDownbeat(i)) 1 else 0)) }
    }
    File(dir, "melody.csv").printWriter().use { w ->
        w.println("start,end,midi,conf")
        for (m in r.melody) w.println("%.4f,%.4f,%d,%.3f".format(m.start, m.end, m.midi, m.confidence))
    }
    File(dir, "novelty.csv").printWriter().use { w ->
        w.println("t,novelty")
        val st = r.structure
        for (i in st.novelty.indices) w.println("%.3f,%.4f".format(st.beatTimes[i], st.novelty[i]))
    }
    File(dir, "sections.csv").printWriter().use { w ->
        w.println("t,label,energy")
        for (s in r.chart.sections) w.println("%.3f,%d,%d".format(s.t / 1000.0, s.label, s.energy))
    }
    File(dir, "energy.csv").printWriter().use { w ->
        w.println("t,intensity,db")
        var t = 0.0
        while (t < r.features.durationSec) {
            w.println("%.2f,%.4f,%.2f".format(t, r.energy.at(t), r.energy.dbAt(t)))
            t += 0.1
        }
    }
    for (ch in r.chart.charts) {
        val prefix = if (ch.lanes == 4) "chart" else "chart${ch.lanes}"
        File(dir, "${prefix}_${ch.difficulty.name.lowercase()}.csv").printWriter().use { w ->
            w.println("t,lane,d")
            for (n in ch.notes) w.println("${n.t},${n.lane},${n.d}")
        }
    }
    println("bpm %.2f subdivisión %d downbeatPhase %d -> ${dir.path}".format(r.grid.bpm, r.grid.subdivision, r.grid.downbeatPhase))
}

private fun usage(): Nothing {
    System.err.println("uso: analyze <archivo|carpeta> [--out dir] | debug <archivo|carpeta> | dump <archivo> [--out dir] | preview <archivo> [--diff EASY|NORMAL|HARD|EXPERT] [--lanes 3|4] [--out dir] [--no-video]")
    exitProcess(2)
}

private fun option(args: List<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

class Pcm(val sampleRate: Int, val channels: Int, val data: FloatArray) {
    val frames: Int get() = data.size / channels
    fun seconds(): Double = frames.toDouble() / sampleRate
}

fun decode(file: File): Pcm {
    val probe = ProcessBuilder(
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=sample_rate,channels", "-of", "csv=p=0", file.path,
    ).redirectErrorStream(true).start()
    val info = probe.inputStream.bufferedReader().readText().trim().lines().first().split(",")
    probe.waitFor()
    val sr = info[0].trim().toInt()
    val ch = min(2, info[1].trim().toInt())
    val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-f", "f32le", "-ac", ch.toString(), "-")
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val bytes = p.inputStream.readBytes()
    p.waitFor()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val data = FloatArray(fb.remaining())
    fb.get(data)
    return Pcm(sr, ch, data)
}

fun analyze(pcm: Pcm): AnalysisResult {
    val a = SongAnalyzer(pcm.sampleRate, pcm.channels)
    val chunkFrames = 4096
    val chunk = FloatArray(chunkFrames * pcm.channels)
    var pos = 0
    while (pos < pcm.frames) {
        val n = min(chunkFrames, pcm.frames - pos)
        System.arraycopy(pcm.data, pos * pcm.channels, chunk, 0, n * pcm.channels)
        a.pushInterleaved(chunk, n)
        pos += n
    }
    return a.finish()
}

private val json = Json { prettyPrint = false }

private fun writeChart(file: File, r: AnalysisResult, out: File) {
    File(out, file.nameWithoutExtension + ".chart.json").writeText(json.encodeToString(r.chart))
}

private fun analyzeFile(file: File, out: File) {
    val t0 = System.nanoTime()
    val pcm = decode(file)
    val t1 = System.nanoTime()
    val r = analyze(pcm)
    val t2 = System.nanoTime()
    writeChart(file, r, out)
    printSummary(file, r, pcm.seconds())
    println("   decodificar %.2fs, analizar %.2fs".format((t1 - t0) / 1e9, (t2 - t1) / 1e9))
}

private fun debugFile(file: File) {
    val pcm = decode(file)
    val r = analyze(pcm)
    val f = r.features
    val env = com.gatotiles.analyzer.rhythm.OnsetDetector.envelopes(f)
    println("== ${file.name}")
    val cands = com.gatotiles.analyzer.rhythm.BeatTracker.tempoCandidates(env.total, f.frameRate, 6)
    println("   candidatos de tempo: " + cands.joinToString { "%.1f(%.2f)".format(it.first, it.second) })
    val period = com.gatotiles.analyzer.rhythm.BeatTracker.estimatePeriod(env.total, f.frameRate)
    val raw = com.gatotiles.analyzer.rhythm.BeatTracker.dpBeats(env.total, period)
    val rawTimes = DoubleArray(raw.size) { raw[it] / f.frameRate }
    println(
        "   dp: %d beats a %.1f bpm, alternancia %.2f; final %.1f bpm, beats seguidos %d..%d de %d".format(
            raw.size, 60.0 * f.frameRate / period,
            com.gatotiles.analyzer.rhythm.BeatTracker.alternationRatio(rawTimes, env.total, f.frameRate),
            r.grid.bpm, r.grid.trackedFrom, r.grid.trackedTo, r.grid.beats.size,
        ),
    )
    if (System.getenv("GT_TEMPOGRAM") != null) {
        val win = (12 * f.frameRate).toInt()
        var a = 0
        while (a + win <= env.total.size) {
            val c = com.gatotiles.analyzer.rhythm.BeatTracker.tempoCandidates(env.total.copyOfRange(a, a + win), f.frameRate, 3)
            println("   %5.1fs: ".format(a / f.frameRate) + c.joinToString { "%.1f(%.1f)".format(it.first, it.second) })
            a += win / 2
        }
    }
    val gen = com.gatotiles.analyzer.chart.ChartGenerator(f.durationSec, r.grid, r.onsets, r.melody, r.energy, 1)
    println("   rejilla local: ${gen.meterSummary()}")
    System.getenv("GT_CANDS")?.let { spec ->
        // GT_CANDS=42-44 or GT_CANDS=42-44:HARD
        val (range, diff) = (spec.split(":") + "EXPERT").let { it[0] to Difficulty.valueOf(it[1].uppercase()) }
        val (a, b) = range.split("-").map { it.toDouble() }
        gen.debugCandidates(diff, a, b).forEach { println("   $it") }
    }
    val pers = r.onsets.map { it.persistence }.sorted()
    val str = r.onsets.map { it.strength }.sorted()
    fun q(l: List<Float>, p: Double) = if (l.isEmpty()) 0f else l[((l.size - 1) * p).toInt()]
    println("   persistencia p10/p50/p90 = %.2f/%.2f/%.2f".format(q(pers, 0.1), q(pers, 0.5), q(pers, 0.9)))
    println("   fuerza p10/p50/p90 = %.2f/%.2f/%.2f; >0.3: %d de %d".format(q(str, 0.1), q(str, 0.5), q(str, 0.9), str.count { it > 0.3f }, str.size))
    val mel = r.melody
    println("   melodía: %d notas, rango midi %d..%d, duración media %.2fs".format(mel.size, mel.minOfOrNull { it.midi } ?: 0, mel.maxOfOrNull { it.midi } ?: 0, mel.map { it.duration }.average()))
    println("   primeras notas: " + mel.take(12).joinToString { "%.2f:%d".format(it.start, it.midi) })
}

private fun printSummary(file: File, r: AnalysisResult, seconds: Double) {
    val c = r.chart
    println("== ${file.name}")
    println(
        "   duración %.1fs  bpm %.1f  subdivisión %d  ataques %d  notas de melodía %d".format(
            seconds, c.bpm, r.grid.subdivision, r.onsets.size, r.melody.size,
        ),
    )
    for (ch in c.charts.filter { it.lanes == 4 }) {
        val n = ch.notes
        val holds = n.count { it.d > 0 }
        val doubles = n.size - n.map { it.t }.distinct().size
        println(
            "   %-8s nivel %2d  teclas %4d  (%.2f/s)  largas %3d  dobles %3d".format(
                ch.difficulty.label, ch.level, n.size, n.size / max(1.0, seconds), holds, doubles,
            ),
        )
    }
    println("   3 carriles: " + c.charts.filter { it.lanes == 3 }.joinToString { "${it.difficulty.label} nivel ${it.level}" })
    println("   partes: " + sectionsText(c.sections, seconds))
    if (System.getenv("GT_SECTIONS") != null) {
        val ps = r.structure.partSim
        for (a in ps.indices) println("     %c ".format('A' + c.sections[a].label) + ps[a].joinToString(" ") { "%.2f".format(it) })
    }
}

private fun sectionsText(sections: List<com.gatotiles.analyzer.model.Section>, seconds: Double): String =
    sections.mapIndexed { i, s ->
        val end = if (i + 1 < sections.size) sections[i + 1].t / 1000.0 else seconds
        "%d:%04.1f %c(%d) %.0fs".format(s.t / 60000, (s.t % 60000) / 1000.0, 'A' + s.label, s.energy, end - s.t / 1000.0)
    }.joinToString("  ")

/** Song + a short click on every tile so sync can be judged by ear. */
private fun writeClickMix(pcm: Pcm, notes: List<Note>, wav: File) {
    val sr = pcm.sampleRate
    val ch = pcm.channels
    val mix = pcm.data.copyOf()
    for (i in mix.indices) mix[i] *= 0.7f
    val clickLen = (0.035 * sr).toInt()
    val click = FloatArray(clickLen) { (0.5 * sin(2 * PI * 1600 * it / sr) * exp(-it / (0.007 * sr))).toFloat() }
    val holdTick = FloatArray(clickLen) { (0.25 * sin(2 * PI * 900 * it / sr) * exp(-it / (0.01 * sr))).toFloat() }
    for (n in notes) {
        addSound(mix, ch, sr, n.t / 1000.0, click)
        if (n.d > 0) addSound(mix, ch, sr, (n.t + n.d) / 1000.0, holdTick)
    }
    DataOutputStream(BufferedOutputStream(FileOutputStream(wav))).use { o ->
        val dataBytes = mix.size * 2
        fun le32(v: Int) = o.writeInt(Integer.reverseBytes(v))
        fun le16(v: Int) = o.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        o.writeBytes("RIFF"); le32(36 + dataBytes); o.writeBytes("WAVE")
        o.writeBytes("fmt "); le32(16); le16(1); le16(ch); le32(sr); le32(sr * ch * 2); le16(ch * 2); le16(16)
        o.writeBytes("data"); le32(dataBytes)
        for (v in mix) le16((v.coerceIn(-1f, 1f) * 32767).roundToInt())
    }
}

private fun addSound(mix: FloatArray, ch: Int, sr: Int, t: Double, s: FloatArray) {
    val start = (t * sr).roundToInt()
    for (i in s.indices) {
        val f = start + i
        if (f < 0 || f * ch + ch > mix.size) break
        for (c in 0 until ch) mix[f * ch + c] += s[i]
    }
}

/** Flat falling-tiles preview at 30 fps, muxed with the click mix. */
private fun renderVideo(r: AnalysisResult, diff: Difficulty, lanes: Int, wav: File, mp4: File) {
    val w = 540
    val h = 960
    val fps = 30
    val notes = r.chart.chart(diff, lanes).notes
    val beats = r.chart.beatsMs
    val durationMs = r.chart.durationMs
    val hitY = (h * 0.82).toInt()
    val approachMs = 1400.0
    val pxPerMs = (hitY - h * 0.12) / approachMs
    val laneW = w / lanes
    val tileH = (h * 0.11).toInt()
    val proc = ProcessBuilder(
        "ffmpeg", "-v", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", "${w}x$h", "-r", fps.toString(), "-i", "-",
        "-i", wav.path, "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k", "-shortest", mp4.path,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val img = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val pixels = (img.raster.dataBuffer as DataBufferByte).data
    val font = Font(Font.SANS_SERIF, Font.BOLD, 28)
    val out = BufferedOutputStream(proc.outputStream, 1 shl 20)
    val totalFrames = ((durationMs + 500) / 1000.0 * fps).toInt()
    var beatIdx = 0
    for (frame in 0 until totalFrames) {
        val now = frame * 1000.0 / fps
        while (beatIdx + 1 < beats.size && beats[beatIdx + 1] <= now) beatIdx++
        val sinceBeat = now - beats.getOrElse(beatIdx) { 0 }
        val pulse = if (sinceBeat in 0.0..250.0) (1 - sinceBeat / 250.0).toFloat() else 0f
        val inten = r.chart.intensity.getOrElse((now / 100).toInt()) { 0 } / 100f
        g.paint = GradientPaint(0f, 0f, Color(20, 12, 40), 0f, h.toFloat(), Color((40 + 60 * inten).toInt(), 20, (70 + 50 * inten).toInt()))
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 255, 255, (25 + 50 * pulse).toInt())
        for (l in 1 until lanes) g.fillRect(l * laneW - 1, 0, 2, h)
        // Beat lines scrolling with the tiles.
        for (bi in max(0, beatIdx - 2) until min(beats.size, beatIdx + 12)) {
            val y = hitY - (beats[bi] - now) * pxPerMs
            if (y < 0 || y > h) continue
            g.color = Color(255, 255, 255, if ((bi - r.chart.downbeatPhase) % 4 == 0) 70 else 25)
            g.fillRect(0, y.toInt(), w, if ((bi - r.chart.downbeatPhase) % 4 == 0) 3 else 1)
        }
        g.color = Color(120, 220, 255, (150 + 100 * pulse).toInt())
        g.stroke = BasicStroke(4f)
        g.drawLine(0, hitY, w, hitY)
        for (n in notes) {
            val dt = n.t - now
            if (dt > approachMs + 200) break
            val endDt = dt + n.d
            if (endDt < -300) continue
            val x = n.lane * laneW + 6
            val yBottom = hitY - dt * pxPerMs
            if (n.d > 0) {
                val yTop = hitY - endDt * pxPerMs
                g.color = if (dt <= 0 && endDt >= 0) Color(120, 230, 255) else Color(60, 90, 160)
                g.fillRoundRect(x + laneW / 3, yTop.toInt(), laneW / 3 - 12, (yBottom - yTop).toInt(), 12, 12)
            }
            val passed = dt < 0
            g.color = if (passed) Color(255, 255, 255, max(0, (255 + dt * 1.5).toInt()).coerceAtMost(255)) else Color(10, 10, 16)
            g.fillRoundRect(x, (yBottom - tileH).toInt(), laneW - 12, tileH, 18, 18)
            g.color = Color(130, 220, 255)
            g.stroke = BasicStroke(2.5f)
            g.drawRoundRect(x, (yBottom - tileH).toInt(), laneW - 12, tileH, 18, 18)
        }
        // Flash lanes whose tile is exactly on the line now.
        for (n in notes) {
            val dt = n.t - now
            if (dt > 40) break
            if (abs(dt) <= 1000.0 / fps) {
                g.color = Color(180, 240, 255, 110)
                g.fillRect(n.lane * laneW, hitY - 40, laneW, 80)
            }
        }
        g.font = font
        g.color = Color.WHITE
        g.drawString("%s  %.0f BPM".format(diff.label, r.chart.bpm), 16, 40)
        val secs = (now / 1000).toInt()
        g.drawString("%d:%02d".format(secs / 60, secs % 60), w - 100, 40)
        out.write(pixels)
    }
    out.close()
    proc.waitFor()
}
