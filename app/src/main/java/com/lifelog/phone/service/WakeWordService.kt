package com.lifelog.phone.service

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lifelog.phone.BuildConfig
import com.lifelog.phone.R
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "WakeWordService"
// How often to upload logger chunks when on cellular and not charging (5 minutes)
private const val CELLULAR_LOGGER_INTERVAL_MS = 5L * 60L * 1000L
private const val CHANNEL_ID = "wake_word_channel"
private const val NOTIFICATION_ID = 1003
private const val WAKEWORD_SENSITIVITY = 0.55f
private const val CAPTURE_MAX_SECONDS = 7
private const val CAPTURE_MIN_SECONDS = 1
private const val CAPTURE_SILENCE_THRESHOLD = 500.0
private const val CAPTURE_EMPTY_COOLDOWN_MS = 2500L
private const val POST_REPLY_COOLDOWN_MS = 1200L

@AndroidEntryPoint
class WakeWordService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_WAKE_STATE = "com.lifelog.phone.WAKE_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var lifeLogApi: LifeLogApi

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val loggerInFlight = AtomicBoolean(false)
    private val lastCellularUploadMs = AtomicLong(0L)

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var notificationManager: NotificationManager? = null

    private var cooldownUntilMs: Long = 0L
    private var wakeSessionId: String = "wake_${UUID.randomUUID().toString().replace("-", "")}"
    private var loggerBuf: ShortArray? = null
    private var loggerPos = 0
    @Volatile private var onSpeechDone: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting wakeword..."))
        setWakeStatus("Starting", "Initializing")
        initTts()
        startDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.get()) startDetector()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { porcupine?.delete() } catch (_: Exception) {}
        porcupine = null
        ttsReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        onSpeechDone = null
        scope.cancel()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "lifelog_wake_reply") finishSpeechTurn()
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == "lifelog_wake_reply") finishSpeechTurn()
                }
            })
            ttsReady = true
            Log.i(TAG, "TTS ready")
        } else {
            ttsReady = false
            Log.w(TAG, "TTS init failed status=$status")
            setWakeStatus("Error", "TTS init failed")
        }
    }

    private fun initTts() {
        try {
            ttsReady = false
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = TextToSpeech(this, this)
    }

    private fun startDetector() {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setWakeStatus("Error", "Microphone permission required")
            return
        }

        running.set(true)
        Thread {
            try {
                val builder = Porcupine.Builder()
                    .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                    .setSensitivity(WAKEWORD_SENSITIVITY)

                val keywordPath = ensureAssetFile("lifelog.ppn")
                builder.setKeywordPath(keywordPath)
                porcupine = builder.build(this)
            } catch (e: PorcupineException) {
                Log.e(TAG, "Porcupine init failed", e)
                running.set(false)
                setWakeStatus("Error", "Wakeword init failed")
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "Wakeword asset setup failed", e)
                running.set(false)
                setWakeStatus("Error", "Wakeword init failed")
                return@Thread
            }

            val p = porcupine ?: run {
                running.set(false)
                return@Thread
            }

            val sampleRate = p.sampleRate
            val frameLength = p.frameLength
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameLength * 4)

            loggerBuf = ShortArray(sampleRate * 60)
            loggerPos = 0

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                setWakeStatus("Error", "Microphone unavailable")
                running.set(false)
                return@Thread
            }

            try {
                audioRecord?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recorder", e)
                setWakeStatus("Error", "Recorder start failed")
                running.set(false)
                return@Thread
            }

            setWakeStatus("Listening", "Say 'LifeLog'")
            Log.i(TAG, "Wake loop started")

            val frame = ShortArray(frameLength)
            while (running.get()) {
                val read = audioRecord?.read(frame, 0, frameLength) ?: 0
                if (read <= 0) {
                    Thread.sleep(30)
                    continue
                }

                appendLoggerSamples(frame, read, sampleRate)

                val now = System.currentTimeMillis()
                if (inFlight.get() || now < cooldownUntilMs) {
                    continue
                }

                val keywordIndex = p.process(frame)
                if (keywordIndex >= 0) {
                    Log.i(TAG, "Wake detected")
                    vibrate()
                    playWakeTone()
                    setWakeStatus("Wake detected", "Listening...")
                    captureAndRespond(sampleRate)
                }
            }
        }.start()
    }

    private fun captureAndRespond(sampleRate: Int) {
        if (!inFlight.compareAndSet(false, true)) return

        val recorder = audioRecord ?: run {
            inFlight.set(false)
            return
        }

        val maxSeconds = CAPTURE_MAX_SECONDS
        val minSeconds = CAPTURE_MIN_SECONDS
        val maxSamples = sampleRate * maxSeconds
        val pcm = ShortArray(maxSamples)
        var offset = 0
        var silenceFrames = 0
        var speechSeen = false
        val frame = (sampleRate / 10).coerceAtLeast(256)
        val silenceThreshold = CAPTURE_SILENCE_THRESHOLD
        setWakeStatus("Recording", "Capture query")

        while (offset + frame <= maxSamples) {
            val read = recorder.read(pcm, offset, frame)
            if (read <= 0) continue
            val rms = rms(pcm, offset, read)
            if (rms > silenceThreshold) {
                speechSeen = true
                silenceFrames = 0
            } else if (speechSeen) {
                silenceFrames += 1
            }
            offset += read
            val seconds = offset / sampleRate
            if (speechSeen && seconds >= minSeconds && silenceFrames >= 5) {
                break
            }
        }

        if (offset < sampleRate / 2) {
            inFlight.set(false)
            cooldownUntilMs = System.currentTimeMillis() + CAPTURE_EMPTY_COOLDOWN_MS
            setWakeStatus("Listening", "Didn't catch that. Say 'LifeLog' again")
            return
        }

        val wav = toWav(pcm.copyOf(offset), sampleRate)
        val baseUrl = settingsRepository.cachedBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            inFlight.set(false)
            setWakeStatus("Error", "Set server URL in Settings")
            return
        }

        setWakeStatus("Thinking", "Contacting server")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                lifeLogApi.voiceQuery(baseUrl, wav, sessionId = wakeSessionId)
            }
            result.onSuccess { (chatRes, _) ->
                val out = chatRes.reply.trim()
                if (out.isEmpty()) {
                    inFlight.set(false)
                    cooldownUntilMs = System.currentTimeMillis() + CAPTURE_EMPTY_COOLDOWN_MS
                    setWakeStatus("Listening", "Say 'LifeLog'")
                    return@onSuccess
                }
                setWakeStatus("Speaking", "Replying")
                speak(out) {
                    inFlight.set(false)
                    cooldownUntilMs = System.currentTimeMillis() + POST_REPLY_COOLDOWN_MS
                    setWakeStatus("Listening", "Say 'LifeLog'")
                    // Notify chat app to refresh when wake word conversation completes
                    val intent = Intent("com.lifelog.phone.REFRESH_CHAT").apply {
                        putExtra("new_message", "true")
                    }
                    sendBroadcast(intent)
                }
            }.onFailure { e ->
                Log.e(TAG, "Wake query failed: ${e.message}", e)
                val detail = e.message?.take(40)?.ifBlank { null } ?: "Wake query failed"
                setWakeStatus("Error", detail)
                inFlight.set(false)
                cooldownUntilMs = System.currentTimeMillis() + CAPTURE_EMPTY_COOLDOWN_MS
                setWakeStatus("Listening", "Say 'LifeLog'")
            }
        }
    }

    private fun appendLoggerSamples(src: ShortArray, read: Int, sampleRate: Int) {
        val buf = loggerBuf ?: return
        var srcPos = 0
        while (srcPos < read) {
            val remain = buf.size - loggerPos
            val n = minOf(remain, read - srcPos)
            System.arraycopy(src, srcPos, buf, loggerPos, n)
            loggerPos += n
            srcPos += n
            if (loggerPos >= buf.size) {
                val chunk = buf.copyOf()
                loggerPos = 0
                postLoggerChunk(chunk, sampleRate)
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        return bm.isCharging
    }

    private fun isLowPowerMode(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isPowerSaveMode
    }

    private fun postLoggerChunk(pcm: ShortArray, sampleRate: Int) {
        if (!loggerInFlight.compareAndSet(false, true)) return
        // Skip logging when media/music is actively playing on the device to avoid
        // transcribing YouTube, podcasts, etc. into the personal log.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.isMusicActive == true) {
            loggerInFlight.set(false)
            return
        }
        // Upload policy based on connectivity and power state:
        //   WiFi                           → upload every chunk (normal rate)
        //   Cellular + charging            → upload every chunk (same as WiFi)
        //   Cellular + low power mode      → skip entirely
        //   Cellular + not charging        → throttle to CELLULAR_LOGGER_INTERVAL_MS
        val onWifi = isOnWifi()
        if (!onWifi) {
            val charging = isCharging()
            if (!charging && isLowPowerMode()) {
                loggerInFlight.set(false)
                return
            }
            if (!charging) {
                val now = System.currentTimeMillis()
                if (now - lastCellularUploadMs.get() < CELLULAR_LOGGER_INTERVAL_MS) {
                    loggerInFlight.set(false)
                    return
                }
            }
        }
        val baseUrl = settingsRepository.cachedBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            loggerInFlight.set(false)
            return
        }
        val token = settingsRepository.cachedToken
        val wav = toWav(pcm, sampleRate)

        val isCellular = !onWifi
        scope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val reqBuilder = Request.Builder()
                    .url("$baseUrl/phone/logger/upload_wav")
                    .post(wav.toRequestBody("audio/wav".toMediaType()))
                if (token.isNotEmpty()) reqBuilder.addHeader("X-Phone-Token", token)
                client.newCall(reqBuilder.build()).execute().close()
                if (isCellular) lastCellularUploadMs.set(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Logger upload failed: ${e.message}")
            } finally {
                loggerInFlight.set(false)
            }
        }
    }

    private fun ensureAssetFile(name: String): String {
        val outFile = File(filesDir, name)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        assets.open(name).use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                }
                output.flush()
            }
        }
        return outFile.absolutePath
    }

    private fun rms(buf: ShortArray, start: Int, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[start + i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / len)
    }

    private fun toWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val byteRate = sampleRate * 2
        val dataLen = pcm.size * 2
        val totalLen = 36 + dataLen

        fun writeStr(s: String) { bos.write(s.toByteArray(Charsets.US_ASCII)) }
        fun writeInt(v: Int) { bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun writeShort(v: Short) { bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()) }

        writeStr("RIFF")
        writeInt(totalLen)
        writeStr("WAVE")
        writeStr("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(2)
        writeShort(16)
        writeStr("data")
        writeInt(dataLen)

        val bb = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        bos.write(bb.array())
        return bos.toByteArray()
    }

    private fun speak(text: String, onDone: () -> Unit) {
        val msg = ttsFriendlyText(text)
        if (msg.isEmpty()) {
            onDone()
            return
        }
        onSpeechDone = onDone
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready, reinitializing")
            initTts()
            scope.launch {
                repeat(10) {
                    delay(100)
                    if (ttsReady) {
                        val rc = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "lifelog_wake_reply")
                        if (rc == TextToSpeech.ERROR) {
                            Log.e(TAG, "TTS speak failed after reinit")
                            setWakeStatus("Error", "TTS speak failed")
                            finishSpeechTurn()
                        }
                        return@launch
                    }
                }
                Log.e(TAG, "TTS never became ready; skipping speech")
                setWakeStatus("Error", "TTS unavailable")
                finishSpeechTurn()
            }
            return
        }
        val rc = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "lifelog_wake_reply")
        if (rc == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak returned ERROR; retrying after reinit")
            setWakeStatus("Error", "TTS speak failed")
            initTts()
            finishSpeechTurn()
            return
        }
        // Safety release in case TTS callback is lost on certain OEM builds.
        scope.launch {
            delay(10_000)
            finishSpeechTurn()
        }
    }

    private fun finishSpeechTurn() {
        val callback = onSpeechDone ?: return
        onSpeechDone = null
        scope.launch(Dispatchers.Main) {
            callback()
        }
    }

    private fun ttsFriendlyText(input: String, maxChars: Int = 1200): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxChars) return text
        val clipped = text.substring(0, maxChars)
        val cut = maxOf(clipped.lastIndexOf('.'), clipped.lastIndexOf('!'), clipped.lastIndexOf('?'))
        return if (cut >= maxChars / 2) clipped.substring(0, cut + 1).trim() else clipped.trim()
    }

    private fun vibrate() {
        try {
            val vib = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(70)
            }
        } catch (_: Exception) {
        }
    }

    private fun playWakeTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 130)
            scope.launch(Dispatchers.IO) {
                delay(220)
                try {
                    tone.release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Wake Word", NotificationManager.IMPORTANCE_LOW)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeLog Wake")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun setWakeStatus(state: String, detail: String) {
        val msg = "$state: $detail"
        Log.i(TAG, msg)
        updateNotification(msg)
        val intent = Intent(ACTION_WAKE_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_DETAIL, detail)
            putExtra("ts", System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }
}
