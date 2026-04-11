package com.lifelog.phone.data.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class WhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "WhisperEngine"
        const val ENCODER_FILE = "whisper-base-encoder.onnx"
        const val DECODER_FILE = "whisper-base-decoder.onnx"
        const val VOCAB_FILE   = "whisper-vocab.json"
        private const val ENCODER_HIDDEN_DIM = 512  // tiny=384, base=512

        private const val SAMPLE_RATE = 16000
        private const val N_FFT       = 512
        private const val HOP_LENGTH  = 160   // 10 ms
        private const val WIN_LENGTH  = 400   // 25 ms
        private const val N_MELS      = 80
        private const val MAX_FRAMES  = 3000  // covers 30 s
        private const val N_SAMPLES   = MAX_FRAMES * HOP_LENGTH  // 480 000

        // Whisper special token IDs
        private const val EOT           = 50256  // <|endoftext|>
        private const val SOT           = 50258  // <|startoftranscript|>
        private const val LANG_EN       = 50259  // <|en|>
        private const val TASK_TRANS    = 50360  // <|transcribe|>
        private const val NO_TIMESTAMPS = 50363  // <|notimestamps|>
        private const val MAX_NEW_TOKENS = 128
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null
    @Volatile private var tokenIdToStr: Map<Int, String>? = null
    @Volatile private var unicodeToBytes: Map<Char, Byte>? = null

    private val hannWindow: FloatArray by lazy {
        FloatArray(WIN_LENGTH) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / WIN_LENGTH))).toFloat() }
    }

    private val melFilterbank: Array<FloatArray> by lazy { buildMelFilterbank() }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true when all three model files are present. */
    fun isReady(): Boolean {
        val dir = context.filesDir
        return java.io.File(dir, ENCODER_FILE).exists() &&
               java.io.File(dir, DECODER_FILE).exists() &&
               java.io.File(dir, VOCAB_FILE).exists()
    }


    /**
     * Transcribe a WAV byte array (44-byte header + PCM-16 LE mono 16 kHz).
     * Returns an empty string if the model is not downloaded or inference fails.
     */
    fun transcribeWav(wavBytes: ByteArray): String {
        if (wavBytes.size <= 44) return ""
        return transcribePcm16(wavBytes.copyOfRange(44, wavBytes.size))
    }

    /** Transcribe raw PCM-16 LE mono 16 kHz bytes. */
    fun transcribePcm16(pcm: ByteArray): String {
        if (!isReady()) return ""
        return runCatching {
            val samples = pcm16ToFloat(pcm)
            val mel     = computeMelSpectrogram(samples)
            val hidden  = runEncoder(mel) ?: return ""
            runDecoder(hidden)
        }.onFailure { Log.e(TAG, "transcription error: ${it.message}") }
         .getOrDefault("")
    }

    // ── Audio pre-processing ──────────────────────────────────────────────────

    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        return FloatArray(n) { i ->
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768.0f
        }
    }

    private fun computeMelSpectrogram(samples: FloatArray): Array<FloatArray> {
        // Pad / truncate to exactly N_SAMPLES
        val padded = FloatArray(N_SAMPLES)
        samples.copyInto(padded, endIndex = minOf(samples.size, N_SAMPLES))

        val nBins  = N_FFT / 2 + 1
        val reArr  = FloatArray(N_FFT)
        val imArr  = FloatArray(N_FFT)

        // result[mel][frame]
        val result = Array(N_MELS) { FloatArray(MAX_FRAMES) }

        for (frame in 0 until MAX_FRAMES) {
            val offset = frame * HOP_LENGTH
            reArr.fill(0f); imArr.fill(0f)
            for (i in 0 until WIN_LENGTH) {
                val s = offset + i
                reArr[i] = if (s < padded.size) padded[s] * hannWindow[i] else 0f
            }
            fft(reArr, imArr)
            // Apply mel filterbank directly from power spectrum
            for (m in 0 until N_MELS) {
                var sum = 0f
                val row = melFilterbank[m]
                for (k in 0 until nBins) {
                    val mag = reArr[k] * reArr[k] + imArr[k] * imArr[k]
                    sum += row[k] * mag
                }
                result[m][frame] = sum
            }
        }

        // Log compression: log10(max(x, 1e-10)), clamp to max−8, normalize to ~[−1,1]
        var maxLog = -Float.MAX_VALUE
        for (m in 0 until N_MELS) {
            for (f in 0 until MAX_FRAMES) {
                val v = log10(result[m][f].coerceAtLeast(1e-10f))
                result[m][f] = v
                if (v > maxLog) maxLog = v
            }
        }
        val floor = maxLog - 8f
        for (m in 0 until N_MELS) {
            for (f in 0 until MAX_FRAMES) {
                result[m][f] = (result[m][f].coerceAtLeast(floor) + 4f) / 4f
            }
        }
        return result
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        val fMin    = 0.0
        val fMax    = SAMPLE_RATE / 2.0
        val nBins   = N_FFT / 2 + 1
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        // N_MELS+2 evenly-spaced mel points
        val pts = DoubleArray(N_MELS + 2) { i -> melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1)) }
        // Map hz to FFT bin indices
        val bins = DoubleArray(N_MELS + 2) { i -> floor(pts[i] * (N_FFT + 1) / SAMPLE_RATE) }

        return Array(N_MELS) { m ->
            FloatArray(nBins) { k ->
                val kd = k.toDouble()
                when {
                    kd <= bins[m] || kd >= bins[m + 2] -> 0f
                    kd <= bins[m + 1] -> ((kd - bins[m]) / (bins[m + 1] - bins[m])).toFloat()
                    else -> ((bins[m + 2] - kd) / (bins[m + 2] - bins[m + 1])).toFloat()
                }
            }
        }
    }

    // ── FFT (iterative Cooley-Tukey, N must be power of 2) ───────────────────

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val half = len / 2
            val ang  = (-2.0 * PI / len).toFloat()
            val wRe  = cos(ang.toDouble()).toFloat()
            val wIm  = sin(ang.toDouble()).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (jj in 0 until half) {
                    val uRe =  re[i + jj]
                    val uIm =  im[i + jj]
                    val vRe =  re[i + jj + half] * curRe - im[i + jj + half] * curIm
                    val vIm =  re[i + jj + half] * curIm + im[i + jj + half] * curRe
                    re[i + jj]       = uRe + vRe;  im[i + jj]       = uIm + vIm
                    re[i + jj + half] = uRe - vRe; im[i + jj + half] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm   = curRe * wIm + curIm * wRe
                    curRe   = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── ONNX encoder ─────────────────────────────────────────────────────────

    private fun runEncoder(mel: Array<FloatArray>): FloatArray? {
        val session = getOrCreateEncoder() ?: return null
        val env     = getOrCreateEnv()

        // Flatten [N_MELS, MAX_FRAMES] → float[] for ORT (shape [1, 80, 3000])
        val flat = FloatArray(N_MELS * MAX_FRAMES)
        for (m in 0 until N_MELS) mel[m].copyInto(flat, m * MAX_FRAMES)

        val shape = longArrayOf(1L, N_MELS.toLong(), MAX_FRAMES.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { inputTensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to inputTensor)).use { out ->
                val t = out.first().value as OnnxTensor
                val fb = t.floatBuffer
                FloatArray(fb.remaining()) { fb.get() }
            }
        }
    }

    // ── ONNX decoder (greedy) ─────────────────────────────────────────────────

    private fun runDecoder(encoderHidden: FloatArray): String {
        val session = getOrCreateDecoder() ?: return ""
        val env     = getOrCreateEnv()

        // encoderHidden shape: [1, 1500, ENCODER_HIDDEN_DIM] — 384 for tiny, 512 for base
        val hiddenShape = longArrayOf(1L, 1500L, ENCODER_HIDDEN_DIM.toLong())

        // Initial prompt tokens for English transcription without timestamps
        val generatedIds = mutableListOf(SOT, LANG_EN, TASK_TRANS, NO_TIMESTAMPS)
        val inputNames = session.inputNames.toList()

        // Track recent token counts to detect repetition loops
        val recentTokenCounts = HashMap<Int, Int>()

        for (step in 0 until MAX_NEW_TOKENS) {
            val idsLong = LongArray(generatedIds.size) { generatedIds[it].toLong() }
            val seqLen  = idsLong.size
            val idShape = longArrayOf(1L, seqLen.toLong())

            val inputs = mutableMapOf<String, OnnxTensor>()
            try {
                val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(idsLong), idShape)
                inputs["input_ids"] = idTensor

                val hidTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderHidden), hiddenShape)
                val hidName = inputNames.firstOrNull { it.contains("encoder") && it.contains("hidden") }
                    ?: inputNames.firstOrNull { it.contains("encoder") }
                    ?: "encoder_hidden_states"
                inputs[hidName] = hidTensor

                session.run(inputs).use { out ->
                    val logits = out.first().value as OnnxTensor
                    val lb = logits.floatBuffer
                    val totalElements = lb.capacity()
                    val vocabSize = totalElements / seqLen
                    // Take logits for last token position
                    val offset = (seqLen - 1) * vocabSize
                    var maxIdx = 0; var maxVal = -Float.MAX_VALUE
                    for (v in 0 until vocabSize) {
                        val x = lb.get(offset + v)
                        if (x > maxVal) { maxVal = x; maxIdx = v }
                    }
                    generatedIds.add(maxIdx)
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }

            val lastToken = generatedIds.last()
            if (lastToken == EOT) break

            // Repetition detection: abort if the same non-special token appears 4+ times in the
            // last 8 tokens, or any single token has been seen 6+ times total in the output.
            if (lastToken < 50000) {  // skip special tokens
                val cnt = (recentTokenCounts[lastToken] ?: 0) + 1
                recentTokenCounts[lastToken] = cnt
                if (cnt >= 5) {
                    Log.w(TAG, "repetition detected at step $step (token $lastToken × $cnt) — truncating")
                    // Trim back to just before the repeat run started
                    val keepUpTo = (generatedIds.size - cnt).coerceAtLeast(4)
                    while (generatedIds.size > keepUpTo) generatedIds.removeLast()
                    break
                }
            }
            // Also stop on 4 identical consecutive tokens
            if (step >= 3) {
                val tail = generatedIds.takeLast(4)
                if (tail.all { it == tail[0] } && tail[0] < 50000) {
                    Log.w(TAG, "4-in-a-row repeat (token ${tail[0]}) at step $step — truncating")
                    val keepUpTo = (generatedIds.size - 4).coerceAtLeast(4)
                    while (generatedIds.size > keepUpTo) generatedIds.removeLast()
                    break
                }
            }
        }

        return decodeTokenIds(generatedIds)
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    private fun loadVocab() {
        if (tokenIdToStr != null) return
        val vocabFile = java.io.File(context.filesDir, VOCAB_FILE)
        if (!vocabFile.exists()) return
        val json = JSONObject(vocabFile.readText())
        val map = HashMap<Int, String>(json.length())
        val it = json.keys()
        while (it.hasNext()) {
            val key = it.next()
            map[json.getInt(key)] = key
        }
        tokenIdToStr = map
    }

    private fun getOrCreateUnicodeToBytes(): Map<Char, Byte> {
        unicodeToBytes?.let { return it }
        val bs = mutableListOf<Int>()
        val cs = mutableListOf<Int>()
        (33..126).forEach  { bs.add(it); cs.add(it) }
        (161..172).forEach { bs.add(it); cs.add(it) }
        (174..255).forEach { bs.add(it); cs.add(it) }
        var n = 0
        for (b in 0..255) {
            if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        }
        return bs.zip(cs).associate { (b, c) -> c.toChar() to b.toByte() }
            .also { unicodeToBytes = it }
    }

    private fun decodeTokenIds(ids: List<Int>): String {
        loadVocab()
        val vocab = tokenIdToStr ?: return ""
        val u2b   = getOrCreateUnicodeToBytes()
        val bytes = mutableListOf<Byte>()
        for (id in ids) {
            val str = vocab[id] ?: continue
            // Skip all special tokens (wrapped in <| |>)
            if (str.startsWith("<|") && str.endsWith("|>")) continue
            str.forEach { ch -> bytes.add(u2b[ch] ?: ' '.code.toByte()) }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    // ── ORT session helpers ───────────────────────────────────────────────────

    @Synchronized
    private fun getOrCreateEnv(): OrtEnvironment {
        env?.let { return it }
        return OrtEnvironment.getEnvironment().also { env = it }
    }

    @Synchronized
    private fun getOrCreateEncoder(): OrtSession? {
        encoderSession?.let { return it }
        val f = java.io.File(context.filesDir, ENCODER_FILE)
        if (!f.exists()) return null
        return runCatching {
            val opts = OrtSession.SessionOptions()
            getOrCreateEnv().createSession(f.absolutePath, opts)
                .also { encoderSession = it }
        }.onFailure { Log.e(TAG, "encoder init failed: ${it.message}") }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateDecoder(): OrtSession? {
        decoderSession?.let { return it }
        val f = java.io.File(context.filesDir, DECODER_FILE)
        if (!f.exists()) return null
        return runCatching {
            val opts = OrtSession.SessionOptions()
            getOrCreateEnv().createSession(f.absolutePath, opts)
                .also { decoderSession = it }
        }.onFailure { Log.e(TAG, "decoder init failed: ${it.message}") }.getOrNull()
    }
}
