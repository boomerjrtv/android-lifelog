package com.lifelog.phone.data.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.lifelog.phone.data.remote.LifeLogApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class EcapaEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    fun embedFromWavBytes(wav: ByteArray): FloatArray? {
        if (wav.size <= 44) return null
        return embedFromPcm16Bytes(wav.copyOfRange(44, wav.size))
    }

    fun assessWavQuality(wav: ByteArray): SpeakerAudioQuality {
        if (wav.size <= 44) return SpeakerAudioQuality(0.0, false, "clip too short")
        return assessPcm16Quality(wav.copyOfRange(44, wav.size))
    }

    fun assessPcm16Quality(buffer: ByteArray?): SpeakerAudioQuality {
        if (buffer == null || buffer.size < 3200) {
            return SpeakerAudioQuality(0.0, false, "audio too short")
        }
        val sampleCount = buffer.size / 2
        var sumAbs = 0.0
        var zcr = 0
        var prev = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val s = (hi shl 8) or lo
            sumAbs += abs(s.toDouble())
            if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) zcr += 1
            prev = s
            i += 2
        }
        val meanAbs = (sumAbs / sampleCount.toDouble()) / 32768.0
        val zcrNorm = zcr.toDouble() / sampleCount.toDouble()
        val energyScore = ((meanAbs - 0.015) / 0.07).coerceIn(0.0, 1.0)
        val zcrScore = (1.0 - abs(zcrNorm - 0.10) / 0.10).coerceIn(0.0, 1.0)
        val score = (energyScore * 0.7 + zcrScore * 0.3).coerceIn(0.0, 1.0)
        val reason = when {
            meanAbs < 0.015 -> "too quiet"
            zcrNorm > 0.22 -> "too noisy"
            else -> "ok"
        }
        return SpeakerAudioQuality(score = score, isUsable = score >= 0.35, reason = reason)
    }

    fun embedFromRecognizerBuffer(buffer: ByteArray?): FloatArray? {
        if (buffer == null || buffer.isEmpty()) return null
        return embedFromPcm16Bytes(buffer)
    }

    private fun embedFromPcm16Bytes(pcm: ByteArray): FloatArray? {
        if (pcm.size < 3200) return null
        val sampleCount = pcm.size / 2
        val samples = FloatArray(sampleCount)
        var bi = 0
        var si = 0
        while (bi + 1 < pcm.size && si < sampleCount) {
            val lo = pcm[bi].toInt() and 0xFF
            val hi = pcm[bi + 1].toInt()
            val s = (hi shl 8) or lo
            samples[si++] = (s / 32768.0f).coerceIn(-1f, 1f)
            bi += 2
        }

        tryOnnx(samples)?.let { return it }
        return fallbackHeuristicEmbedding(pcm)
    }

    private fun tryOnnx(samples: FloatArray): FloatArray? {
        val modelFile = java.io.File(context.filesDir, LifeLogApi.SPEAKER_MODEL_FILE)
        if (!modelFile.exists() || modelFile.length() < 1024L) return null

        return runCatching {
            val s = getOrCreateSession(modelFile)
            val inputName = s.inputNames.firstOrNull() ?: return null
            val inputShape = longArrayOf(1L, samples.size.toLong())
            OnnxTensor.createTensor(getOrCreateEnv(), FloatBuffer.wrap(samples), inputShape).use { inputTensor ->
                s.run(mapOf(inputName to inputTensor)).use { out ->
                    val firstTensor = out.firstOrNull()?.value as? OnnxTensor ?: return null
                    val fb = firstTensor.floatBuffer
                    val vec = FloatArray(fb.remaining()) { fb.get() }
                    if (vec.isEmpty()) return null
                    normalize(vec)
                }
            }
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateEnv(): OrtEnvironment {
        env?.let { return it }
        return OrtEnvironment.getEnvironment().also { env = it }
    }

    @Synchronized
    private fun getOrCreateSession(modelFile: java.io.File): OrtSession {
        session?.let { return it }
        val options = OrtSession.SessionOptions()
        return getOrCreateEnv().createSession(modelFile.absolutePath, options).also { session = it }
    }

    private fun fallbackHeuristicEmbedding(buffer: ByteArray): FloatArray {
        val sampleCount = buffer.size / 2
        var sumAbs = 0.0
        var zcr = 0
        var prev = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val s = (hi shl 8) or lo
            sumAbs += abs(s.toDouble())
            if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) zcr += 1
            prev = s
            i += 2
        }

        val meanAbs = (sumAbs / sampleCount.toDouble()) / 32768.0
        val zcrNorm = zcr.toDouble() / sampleCount.toDouble()
        val vec = floatArrayOf(
            meanAbs.toFloat(),
            zcrNorm.toFloat(),
            (meanAbs * (1.0 - zcrNorm)).toFloat(),
            (meanAbs * zcrNorm).toFloat(),
        )
        return normalize(vec)
    }

    fun serialize(vec: FloatArray): String = vec.joinToString(",") { it.toString() }

    fun parse(serialized: String): FloatArray? {
        val clean = serialized.trim()
        if (clean.isEmpty()) return null
        val parts = clean.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.isEmpty()) return null
        return normalize(parts.toFloatArray())
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom <= 1e-9) return 0.0
        return dot / denom
    }

    fun blendEmbeddings(existing: FloatArray?, incoming: FloatArray, existingCount: Int): FloatArray {
        if (existing == null || existing.isEmpty() || existingCount <= 0) return normalize(incoming)
        val n = minOf(existing.size, incoming.size)
        if (n <= 0) return normalize(incoming)
        val oldWeight = existingCount.toDouble().coerceAtLeast(1.0)
        val newWeight = 1.0
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (((existing[i] * oldWeight) + (incoming[i] * newWeight)) / (oldWeight + newWeight)).toFloat()
        }
        return normalize(out)
    }

    private fun normalize(vec: FloatArray): FloatArray {
        var sum = 0.0
        vec.forEach { sum += it * it }
        val norm = sqrt(sum)
        if (norm <= 1e-9) return vec
        return FloatArray(vec.size) { idx -> (vec[idx] / norm).toFloat() }
    }
}

data class SpeakerAudioQuality(
    val score: Double,
    val isUsable: Boolean,
    val reason: String,
)
