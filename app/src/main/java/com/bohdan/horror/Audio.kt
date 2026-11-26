package com.bohdan.horror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object Audio {
    private const val SR = 22050
    private val cache = HashMap<String, ShortArray>()
    private val loops = HashMap<String, AudioTrack>()
    private val oneShots = ArrayList<AudioTrack>()
    private val rnd = Random(5)
    @Volatile private var master = 1f
    private val loopVol = HashMap<String, Float>()

    @Synchronized
    fun setMaster(v: Float) {
        master = v
        for ((k, t) in loops) { try { t.setVolume((loopVol[k] ?: 1f) * v) } catch (e: Exception) { } }
    }

    private fun gen(sec: Float, f: (Float) -> Float): ShortArray {
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = f(i.toFloat() / SR)
            out[i] = (max(-1f, min(1f, v)) * 30000f).toInt().toShort()
        }
        return out
    }

    private fun noise(): Float = rnd.nextFloat() * 2f - 1f
    private fun s(x: Float): Float = sin(2.0 * PI * x).toFloat()
    private fun saw(x: Float): Float = (x - Math.floor(x.toDouble()).toFloat()) * 2f - 1f

    private fun make(name: String): ShortArray = when (name) {
        "scare" -> gen(2.6f) { t ->
            val env = exp(-t * 1.1f) * min(1f, t * 40f)
            val f = 900f - 500f * t
            (saw(t * f) * 0.35f + saw(t * f * 1.06f) * 0.3f + s(t * 70f) * 0.4f + noise() * 0.35f) * env
        }
        "drone" -> gen(6f) { t -> (s(t * 55f) * 0.35f + s(t * 58f) * 0.3f + s(t * 110.5f) * 0.1f + noise() * 0.03f * (1f + s(t / 3f))) * 0.7f }
        "tense" -> gen(8f) { t ->
            val notes = floatArrayOf(110f, 130.8f, 164.8f, 130.8f, 98f, 123.5f, 146.8f, 123.5f)
            val step = (t * 4f).toInt() % 8
            val lt = (t * 4f) - Math.floor((t * 4f).toDouble()).toFloat()
            val env = exp(-lt * 3.5f)
            val pulse = (if (((t * 2f) % 1f) < 0.12f) 1f else 0f) * s(t * 48f) * 0.5f
            (s(t * notes[step]) * 0.35f * env + saw(t * notes[step] * 0.5f) * 0.15f + pulse + s(t * (400f + 40f * t)) * 0.05f * (t / 8f))
        }
        "whistle" -> gen(1.0f) { t ->
            val env = min(1f, t * 30f) * min(1f, (1f - t) * 12f)
            (s(t * (2500f + 80f * s(t * 9f))) * 0.6f + noise() * 0.12f) * env
        }
        "siren" -> gen(2.4f) { t ->
            val f = 700f + 300f * (0.5f + 0.5f * s(t / 1.2f))
            s(t * f) * 0.4f + s(t * f * 2f) * 0.1f
        }
        "knock" -> gen(0.25f) { t -> (s(t * 80f) * 0.7f + noise() * 0.3f) * exp(-t * 18f) }
        "break" -> gen(1.0f) { t -> (noise() * 0.8f + s(t * 60f) * 0.5f) * exp(-t * 5f) }
        "shock" -> gen(0.8f) { t -> (if (abs(noise()) > 0.5f) noise() else s(t * 300f) * 0.4f) * exp(-t * 3f) }
        "scream" -> gen(1.8f) { t ->
            val env = min(1f, t * 8f) * exp(-t * 0.9f)
            (s(t * (900f + 400f * s(t * 5f) + 200f * t)) * 0.4f + noise() * 0.12f) * env * 0.7f
        }
        "shot" -> gen(0.8f) { t -> (noise() * 0.9f + s(t * 90f) * 0.6f) * exp(-t * 9f) }
        "step" -> gen(0.12f) { t -> noise() * exp(-t * 40f) * 0.4f }
        "splat" -> gen(0.35f) { t -> (noise() * 0.7f + s(t * 120f) * 0.3f) * exp(-t * 14f) }
        "ring" -> gen(1.6f) { t -> (if ((t % 1.2f) < 0.4f) s(t * 425f) * 0.3f else 0f) }
        "beep" -> gen(0.12f) { t -> (s(t * 697f) + s(t * 1209f)) * 0.25f }
        "thud" -> gen(0.5f) { t -> s(t * 55f) * exp(-t * 9f) * 0.9f }
        "end" -> gen(4.0f) { t ->
            val env = exp(-t * 0.5f) * min(1f, t * 20f)
            (saw(t * (120f - 20f * t)) * 0.4f + saw(t * (123f - 20f * t)) * 0.4f + s(t * 38f) * 0.5f + noise() * 0.25f * exp(-t * 1.5f)) * env
        }
        else -> ShortArray(100)
    }

    private fun data(name: String): ShortArray = cache.getOrPut(name) { make(name) }

    private fun track(d: ShortArray, vol: Float): AudioTrack? {
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(d.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(d, 0, d.size)
            t.setVolume(vol * master)
            t
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun play(name: String, vol: Float = 1f) {
        val t = track(data(name), vol) ?: return
        try { t.play() } catch (e: Exception) { }
        oneShots.add(t)
        if (oneShots.size > 24) {
            val old = oneShots.removeAt(0)
            try { old.stop(); old.release() } catch (e: Exception) { }
        }
    }

    @Synchronized
    fun loop(name: String, vol: Float = 1f) {
        if (loops.containsKey(name)) return
        val d = data(name)
        val t = track(d, vol) ?: return
        try {
            t.setLoopPoints(0, d.size, -1)
            t.play()
        } catch (e: Exception) { }
        loops[name] = t
        loopVol[name] = vol
    }

    @Synchronized
    fun stopLoop(name: String) {
        val t = loops.remove(name) ?: return
        try { t.stop(); t.release() } catch (e: Exception) { }
    }

    @Synchronized
    fun stopAll() {
        for (t in loops.values) try { t.stop(); t.release() } catch (e: Exception) { }
        loops.clear()
        for (t in oneShots) try { t.stop(); t.release() } catch (e: Exception) { }
        oneShots.clear()
    }
}
