package com.lifelog.phone.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lifelog.phone.R
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.local.PhoneLogDao
import com.lifelog.phone.data.local.PhoneLogEntity
import com.lifelog.phone.data.local.SpeakerProfileDao
import com.lifelog.phone.data.local.SpeakerProfileEntity
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.data.speaker.EcapaEmbeddingEngine
import com.lifelog.phone.data.speaker.SpeakerClusterAssigner
import com.lifelog.phone.data.whisper.WhisperEngine
import com.lifelog.phone.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "WakeWordService"
private const val CHANNEL_ID = "wake_word_channel"
private const val NOTIFICATION_ID = 1003

@AndroidEntryPoint
class WakeWordService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_WAKE_STATE     = "com.lifelog.phone.WAKE_STATE"
        const val EXTRA_STATE           = "state"
        const val EXTRA_DETAIL          = "detail"
        const val ACTION_START_LISTENING = "com.lifelog.phone.action.WAKEWORD_START"
        const val ACTION_STOP_LISTENING  = "com.lifelog.phone.action.WAKEWORD_STOP"

        private const val SAMPLE_RATE   = 16_000
        private const val CHUNK_MS      = 10_000L           // 10 s per transcription chunk
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2 // PCM-16 → 2 bytes/sample

        private val WAKE_PHRASES = listOf("lifelog", "life log", "hey lifelog", "hey life log")

        // Mean absolute amplitude (0-1 scale) below which a chunk is considered silent.
        // 0.005 ≈ very quiet room; lower = more sensitive, more Whisper calls on silence.
        private const val VAD_THRESHOLD = 0.005
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var lifeLogApi: LifeLogApi
    @Inject lateinit var phoneLogDao: PhoneLogDao
    @Inject lateinit var speakerProfileDao: SpeakerProfileDao
    @Inject lateinit var speakerClusterAssigner: SpeakerClusterAssigner
    @Inject lateinit var ecapaEmbeddingEngine: EcapaEmbeddingEngine
    @Inject lateinit var whisperEngine: WhisperEngine

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var notificationManager: NotificationManager? = null
    private var audioManager: AudioManager? = null

    @Volatile private var listening = false
    @Volatile private var wakeInFlight = false
    private var wakeSessionId = newSessionId()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val processingBusy = AtomicBoolean(false)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager        = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action ?: "<null>"} listening=$listening")
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                if (!listening) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.i(TAG, "start requested, beginning audio capture")
                        startAudioCapture()
                    } else {
                        Log.w(TAG, "start requested without microphone permission")
                        setWakeStatus("Error", "Microphone permission required")
                    }
                }
            }
            ACTION_STOP_LISTENING -> stopCapture()
            else -> stopSelf()   // ignore sticky/implicit restarts
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        tts?.stop(); tts?.shutdown(); tts = null
        scope.cancel()
    }

    // ── AudioRecord capture loop ───────────────────────────────────────────────

    private fun startAudioCapture() {
        if (listening) return
        Log.i(TAG, "startAudioCapture")
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord min buffer unavailable: $minBuf")
            setWakeStatus("Error", "AudioRecord unavailable on this device")
            return
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, CHUNK_BYTES)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            rec.release()
            return
        }
        audioRecord = rec
        listening   = true
        rec.startRecording()
        setWakeStatus("Listening", "Always-on transcription active")

        recordingThread = Thread {
            val buf    = ByteArray(CHUNK_BYTES)
            var offset = 0
            var chunkCount = 0
            Log.i(TAG, "recording thread started, CHUNK_BYTES=$CHUNK_BYTES")
            while (listening) {
                val toRead = CHUNK_BYTES - offset
                val n = rec.read(buf, offset, toRead)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read returned error $n — stopping")
                    break
                }
                if (n == 0) { Thread.sleep(10); continue }
                offset += n
                if (offset < CHUNK_BYTES) continue   // chunk not full yet

                val chunk = buf.copyOf(CHUNK_BYTES)
                offset = 0
                chunkCount++

                val energy = chunkEnergy(chunk)
                Log.d(TAG, "chunk #$chunkCount energy=${"%.4f".format(energy)} busy=${processingBusy.get()}")

                // Skip if silent — avoids wasting Whisper cycles on quiet rooms
                if (energy < VAD_THRESHOLD) {
                    Log.d(TAG, "chunk #$chunkCount silent (energy=${"%.4f".format(energy)} < $VAD_THRESHOLD), skipping")
                    continue
                }

                // Skip if still transcribing the previous chunk
                if (!processingBusy.compareAndSet(false, true)) {
                    Log.d(TAG, "chunk #$chunkCount: still processing previous, skipping")
                    continue
                }

                scope.launch(Dispatchers.IO) {
                    try { processChunk(chunk) }
                    catch (e: Exception) { Log.e(TAG, "processChunk error: ${e.message}", e) }
                    finally { processingBusy.set(false) }
                }
            }
            Log.i(TAG, "recording thread exited after $chunkCount chunks")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopCapture() {
        listening = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        wakeInFlight = false
        stopSelf()
    }

    // ── Chunk processing: Whisper + ECAPA + DB ─────────────────────────────────

    private suspend fun processChunk(pcm: ByteArray) {
        if (!whisperEngine.isReady()) {
            Log.d(TAG, "Whisper model not yet downloaded, skipping chunk")
            return
        }

        // 1 — Transcribe
        val rawText = whisperEngine.transcribePcm16(pcm).trim()
        if (rawText.length < 6) { Log.d(TAG, "too short, skipping: '$rawText'"); return }

        // Filter common Whisper hallucinations on quiet/ambient audio
        val text = rawText
        if (isLikelyHallucination(text)) { Log.d(TAG, "hallucination filtered: '$text'"); return }
        Log.i(TAG, "transcript: $text")

        // 2 — ECAPA speaker embedding from the same PCM buffer
        val quality   = ecapaEmbeddingEngine.assessPcm16Quality(pcm)
        val embedding = if (quality.isUsable) ecapaEmbeddingEngine.embedFromRecognizerBuffer(pcm) else null
        val threshold = if (quality.isUsable) (0.84 + (1.0 - quality.score) * 0.08).coerceIn(0.84, 0.92) else null

        // 3 — Speaker assignment
        val ts       = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.LocalDateTime.now())
        val slot     = normalizedSpeakerSlot(settingsRepository.cachedActiveSpeakerSlot)
        val recent   = phoneLogDao.getByKind("transcript", 80)
        val profiles = speakerProfileDao.getAll()
        val decision = speakerClusterAssigner.assign(
            transcriptText    = text,
            transcriptTs      = ts,
            activeSlot        = slot,
            recentTranscripts = recent,
            profiles          = profiles,
            embeddingSimilarity = if (embedding == null) null else { profile ->
                val pv = ecapaEmbeddingEngine.parse(profile.embedding)
                if (pv == null) 0.0 else ecapaEmbeddingEngine.cosine(embedding, pv)
            },
            embeddingThresholdOverride = threshold,
        )

        // 4 — Persist to DB
        phoneLogDao.insert(
            PhoneLogEntity(
                id                = System.currentTimeMillis(),
                ts                = ts,
                deviceId          = Build.MODEL,
                kind              = "transcript",
                text              = text,
                transcriptId      = 0L,
                sourceType        = "on_device_asr",
                speakerId         = decision.speakerId,
                speakerConfidence = decision.confidence,
                tags              = "",
                importance        = 0.8,
                mediaLikelihood   = 0.0,
                dialogDensity     = 0.0,
                source            = "whisper_audiorecord",
                speakerClusterId  = decision.clusterId,
            )
        )

        // 5 — Update speaker profile embedding (running average)
        if (decision.clusterId.isNotBlank()) {
            val current     = speakerProfileDao.getByClusterId(decision.clusterId)
            val existingVec = current?.embedding?.let { ecapaEmbeddingEngine.parse(it) }
            val blended     = if (embedding != null)
                ecapaEmbeddingEngine.blendEmbeddings(existingVec, embedding, current?.sampleCount ?: 0)
            else existingVec
            speakerProfileDao.upsert(
                SpeakerProfileEntity(
                    clusterId   = decision.clusterId,
                    displayName = decision.speakerId,
                    embedding   = blended?.let { ecapaEmbeddingEngine.serialize(it) } ?: (current?.embedding ?: ""),
                    sampleCount = (current?.sampleCount ?: 0) + 1,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }

        // 6 — Wake phrase check
        if (containsWakePhrase(text) && !wakeInFlight) {
            mainHandler.post { handleWake(text) }
        }
    }

    // ── Voice Activity Detection ───────────────────────────────────────────────

    private fun chunkEnergy(pcm: ByteArray): Double {
        if (pcm.size < 3200) return 0.0
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val s  = (hi shl 8) or lo
            sum += if (s < 0) -s.toLong() else s.toLong()
            i += 2
        }
        return (sum.toDouble() / (pcm.size / 2)) / 32768.0
    }

    private fun isLikelyHallucination(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isBlank()) return true
        if (clean.length < 6) return true
        val tokens = clean.split(Regex("\\s+"))
        if (tokens.size >= 6) {
            val uniq = tokens.toSet().size
            if (uniq <= 2) return true
        }
        return Regex("(.)\\1{5,}").containsMatchIn(clean)
    }

    // ── Wake flow ──────────────────────────────────────────────────────────────

    private fun containsWakePhrase(text: String): Boolean {
        val lower = text.lowercase().trim()
        return WAKE_PHRASES.any { lower.contains(it) }
    }

    private fun handleWake(rawText: String) {
        wakeInFlight = true
        setWakeStatus("Wake detected", "Thinking…")
        Log.i(TAG, "Wake phrase detected: $rawText")

        val query = WAKE_PHRASES.fold(rawText.lowercase()) { acc, p -> acc.replace(p, "") }
            .trim().replaceFirstChar { it.uppercase() }
        val prompt  = query.ifBlank { "What's going on?" }
        val baseUrl = settingsRepository.cachedLifeLogSyncUrl
            .ifBlank { settingsRepository.lifeLogSyncUrl }.trim()

        scope.launch(Dispatchers.IO) {
            val result = lifeLogApi.chatWithMeta(baseUrl = baseUrl, text = prompt, sessionId = wakeSessionId)
            val reply  = result.getOrNull()?.reply?.trim()
                ?: result.exceptionOrNull()?.message?.take(80)
                ?: "Sorry, something went wrong."
            mainHandler.post {
                setWakeStatus("Speaking", "Replying")
                speak(reply)
                sendBroadcast(Intent("com.lifelog.phone.REFRESH_CHAT").apply {
                    setPackage(packageName); putExtra("new_message", "true")
                })
            }
        }
    }

    private fun speak(text: String) {
        val msg = text.take(1200).trim()
        if (msg.isEmpty() || !ttsReady || tts == null) {
            wakeInFlight = false
            return
        }
        val rc = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "lifelog_wake_reply")
        if (rc == TextToSpeech.ERROR) wakeInFlight = false
        // Safety timeout in case TTS completion callback is missed
        mainHandler.postDelayed({ if (wakeInFlight) { wakeInFlight = false; wakeSessionId = newSessionId() } }, 12_000L)
    }

    // ── TTS ────────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    mainHandler.post {
                        wakeInFlight  = false
                        wakeSessionId = newSessionId()
                        setWakeStatus("Listening", "Always-on transcription active")
                    }
                }
                override fun onError(id: String?) {
                    mainHandler.post { wakeInFlight = false }
                }
            })
        }
    }

    private fun initTts() {
        tts?.stop(); tts?.shutdown()
        tts = TextToSpeech(this, this)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun normalizedSpeakerSlot(raw: String): String {
        val clean = raw.trim().uppercase()
        return if (clean.matches(Regex("S[0-9]+"))) clean else ""
    }

    private fun isMediaPlaybackActive() = audioManager?.isMusicActive == true

    private fun newSessionId() = "wake_${UUID.randomUUID().toString().replace("-", "")}"

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake Word", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeLog")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setWakeStatus(state: String, detail: String) {
        val msg = "$state: $detail"
        Log.i(TAG, msg)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(msg))
        sendBroadcast(Intent(ACTION_WAKE_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state); putExtra(EXTRA_DETAIL, detail)
            putExtra("ts", System.currentTimeMillis())
        })
    }
}
