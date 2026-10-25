package com.grokrun.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.grokrun.data.WhistleId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Apitos sintetizados, iguais aos da web (audio.ts / playWhistle):
 * cada "piu" é um seno com varredura exponencial de frequência + um sopro de ruído filtrado.
 */
object Whistles {
    private const val RATE = 22050
    private val cache = HashMap<WhistleId, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    private class Peep(val at: Double, val dur: Double, val f0: Double, val f1: Double, val gain: Double = 0.55)

    private fun recipe(id: WhistleId): List<Peep> = when (id) {
        WhistleId.CURTO -> listOf(Peep(0.0, 0.16, 2500.0, 2100.0))
        WhistleId.LONGO -> listOf(Peep(0.0, 0.75, 2200.0, 1700.0))
        WhistleId.DUPLO -> listOf(Peep(0.0, 0.14, 2700.0, 2400.0), Peep(0.2, 0.14, 2800.0, 2500.0))
        WhistleId.TRIPLO -> listOf(0.0, 0.16, 0.32).map { Peep(it, 0.1, 2700.0, 2450.0) }
        WhistleId.SOBE -> listOf(Peep(0.0, 0.46, 1200.0, 3200.0))
        WhistleId.DESCE -> listOf(Peep(0.0, 0.55, 3000.0, 780.0))
        WhistleId.AGUDO -> listOf(Peep(0.0, 0.18, 3400.0, 3700.0))
        WhistleId.TRINADO -> (0 until 6).map { Peep(it * 0.08, 0.06, if (it % 2 == 0) 2900.0 else 2500.0, if (it % 2 == 0) 2600.0 else 2800.0, 0.45) }
    }

    /** Duração total em ms (para segurar o foco de áudio). */
    fun durationMs(id: WhistleId): Long = (recipe(id).maxOf { it.at + it.dur } * 1000).toLong() + 60

    fun pcm(id: WhistleId): ShortArray = cache.getOrPut(id) {
        val peeps = recipe(id)
        val total = peeps.maxOf { it.at + it.dur } + 0.05
        val buf = DoubleArray((total * RATE).toInt())
        val rnd = Random(7)
        for (p in peeps) {
            val n = (p.dur * RATE).toInt()
            val start = (p.at * RATE).toInt()
            val attack = min(0.03, p.dur * 0.25)
            var phase = 0.0
            // passa-banda simples (biquad) para o sopro de ar
            val fc = (p.f0 + p.f1) / 2
            val w0 = 2 * PI * fc / RATE
            val q = 4.0
            val alpha = sin(w0) / (2 * q)
            val b0 = alpha; val a0 = 1 + alpha; val a1 = -2 * cos(w0); val a2 = 1 - alpha
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val f = p.f0 * (p.f1 / p.f0).pow(t / p.dur)
                phase += 2 * PI * f / RATE
                val env = if (t < attack) t / attack else exp(ln(0.0015) * (t - attack) / max(0.001, p.dur - attack))
                // leve vibrato, como apito de verdade
                val tone = sin(phase + 0.35 * sin(2 * PI * 38 * t))
                val x = rnd.nextDouble(-1.0, 1.0)
                val y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0
                x2 = x1; x1 = x; y2 = y1; y1 = y
                val air = if (t < min(0.08, p.dur)) y * 0.9 else y * 0.25
                val k = start + i
                if (k < buf.size) buf[k] += p.gain * env * (tone + air)
            }
        }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        val norm = if (peak > 0.95) 0.95 / peak else 1.0
        ShortArray(buf.size) { (buf[it] * norm * Short.MAX_VALUE).toInt().toShort() }
    }

    fun vibration(id: WhistleId): LongArray = when (id) {
        WhistleId.CURTO, WhistleId.AGUDO -> longArrayOf(0, 60)
        WhistleId.LONGO -> longArrayOf(0, 260)
        WhistleId.DUPLO -> longArrayOf(0, 40, 60, 40)
        WhistleId.TRIPLO -> longArrayOf(0, 40, 50, 40, 50, 40)
        WhistleId.SOBE, WhistleId.DESCE -> longArrayOf(0, 120, 40, 120)
        WhistleId.TRINADO -> longArrayOf(0, 30, 40, 30, 40, 30, 40, 30)
    }

    /** Toca uma vez; o AudioTrack é liberado sozinho depois do fim. */
    fun play(id: WhistleId) {
        runCatching {
            val data = pcm(id)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            main.postDelayed({ runCatching { track.stop(); track.release() } }, durationMs(id) + 300)
        }
    }
}
