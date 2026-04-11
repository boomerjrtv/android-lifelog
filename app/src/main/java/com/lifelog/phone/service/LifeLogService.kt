package com.lifelog.phone.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.provider.CalendarContract
import android.location.Location
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.lifelog.phone.data.local.CalendarEventDao
import com.lifelog.phone.data.local.CalendarEventEntity
import com.lifelog.phone.data.local.PhoneLogDao
import com.lifelog.phone.data.local.PhoneLogEntity
import com.lifelog.phone.data.remote.CalendarSyncEvent
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.data.MessageRepository
import com.lifelog.phone.data.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class LifeLogService : Service() {

    @Inject lateinit var settingsRepository: com.lifelog.phone.data.SettingsRepository
    @Inject lateinit var lifeLogApi: LifeLogApi
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var phoneLogDao: PhoneLogDao
    @Inject lateinit var calendarEventDao: CalendarEventDao
    @Inject lateinit var whisperEngine: WhisperEngine

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var lastCalendarSyncAtMs: Long = 0L
    private val reminderPrefs by lazy {
        getSharedPreferences("lifelog_reminders", Context.MODE_PRIVATE)
    }
    private val maintenancePrefs by lazy {
        getSharedPreferences("lifelog_maintenance", Context.MODE_PRIVATE)
    }
    private var dwellAnchorLat: Double? = null
    private var dwellAnchorLon: Double? = null
    private var dwellAnchorStartMs: Long = 0L
    private var lastDwellPromptLat: Double? = null
    private var lastDwellPromptLon: Double? = null
    @Volatile private var transcriptionActive: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val transcriptionBusy = AtomicBoolean(false)
    @Volatile private var pendingTranscriptionChunk: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        startHeartbeatLoop()
        lifeLogApi.warmUpOnDeviceEngine()
        lifeLogApi.ensureWhisperDownloaded()
        scheduleTranscriptCleanup()
    }

    private fun startAlwaysOnTranscriptionIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i("LifeLogService", "startAlwaysOnTranscriptionIfPermitted granted=$granted active=$transcriptionActive")
        if (!granted) {
            setWakeStatus("Mic", "Permission missing")
            return
        }
        setWakeStatus("Mic", "Starting")
        startTranscriptionCapture()
    }

    private fun scheduleTranscriptCleanup() {
        val now = System.currentTimeMillis()
        val lastRun = maintenancePrefs.getLong(KEY_TRANSCRIPT_CLEANUP_AT_MS, 0L)
        if (now - lastRun < TRANSCRIPT_CLEANUP_INTERVAL_MS) return
        scope.launch {
            runCatching {
                cleanupHistoricalTranscriptNoise()
                maintenancePrefs.edit().putLong(KEY_TRANSCRIPT_CLEANUP_AT_MS, now).apply()
            }.onFailure { e ->
                Log.w("LifeLogService", "transcript cleanup failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSCRIPTION_START -> startAlwaysOnTranscriptionIfPermitted()
            ACTION_TRANSCRIPTION_STOP -> stopTranscriptionCapture()
            ACTION_LOCATION_CONFIRMATION -> {
                scope.launch { handleLocationConfirmation(intent) }
            }
            ACTION_LOCATION_TEST_PROMPT -> {
                val place = intent.getStringExtra(EXTRA_LOCATION_PLACE).orEmpty().trim()
                    .ifBlank { "this location" }
                var lat = intent.getDoubleExtra(EXTRA_LOCATION_LAT, Double.NaN)
                var lon = intent.getDoubleExtra(EXTRA_LOCATION_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) {
                    val current = buildLocationPayload()
                    lat = current?.get("latitude")?.asDouble ?: Double.NaN
                    lon = current?.get("longitude")?.asDouble ?: Double.NaN
                }
                if (canPostNotifications()) {
                    sendLocationCheckInNotification(place, lat, lon)
                }
            }
            ACTION_MEAL_TEST_PROMPT -> {
                if (canPostNotifications()) {
                    val key = intent.getStringExtra(EXTRA_MEAL_KEY).orEmpty().trim().lowercase()
                    val reminder = MEAL_REMINDERS.firstOrNull { it.key == key }
                        ?: MEAL_REMINDERS.firstOrNull { it.key == "breakfast" }
                    if (reminder != null) {
                        sendMealReminderNotification(
                            reminder = reminder,
                            isFollowUp = false,
                            trackPending = true
                        )
                    }
                }
            }
            ACTION_MEAL_REPLY -> {
                scope.launch { handleMealReply(intent) }
            }
            ACTION_SPEAKER_REPLY -> {
                scope.launch { handleSpeakerReply(intent) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTranscriptionCapture()
        scope.cancel()
    }

    private fun startTranscriptionCapture() {
        if (transcriptionActive) return
        val minBuf = AudioRecord.getMinBufferSize(
            TRANSCRIPTION_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e("LifeLogService", "AudioRecord unavailable: $minBuf")
            setWakeStatus("Mic", "Audio unavailable")
            return
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            TRANSCRIPTION_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, TRANSCRIPTION_CHUNK_BYTES)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("LifeLogService", "AudioRecord init failed")
            rec.release()
            setWakeStatus("Mic", "Init failed")
            return
        }
        audioRecord = rec
        transcriptionActive = true
        rec.startRecording()
        Log.i("LifeLogService", "always-on transcription started")
        setWakeStatus("Mic", "Running")
        recordingThread = Thread {
            val buf = ByteArray(TRANSCRIPTION_CHUNK_BYTES)
            var offset = 0
            while (transcriptionActive) {
                val n = rec.read(buf, offset, TRANSCRIPTION_CHUNK_BYTES - offset)
                if (n < 0) {
                    Log.w("LifeLogService", "AudioRecord.read error $n")
                    break
                }
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                offset += n
                if (offset < TRANSCRIPTION_CHUNK_BYTES) continue
                val chunk = buf.copyOf(TRANSCRIPTION_CHUNK_BYTES)
                offset = 0
                val energy = chunkEnergy(chunk)
                Log.d("LifeLogService", "chunk captured, energy=${"%.4f".format(energy)}")
                if (energy < TRANSCRIPTION_VAD_THRESHOLD) {
                    Log.d("LifeLogService", "chunk silent (energy $energy < $TRANSCRIPTION_VAD_THRESHOLD), skipping")
                    continue
                }
                if (!transcriptionBusy.compareAndSet(false, true)) {
                    pendingTranscriptionChunk = chunk
                    Log.d("LifeLogService", "transcription busy, queued latest chunk")
                    continue
                }
                scope.launch {
                    try {
                        Log.d("LifeLogService", "starting transcription of chunk...")
                        persistTranscriptChunk(chunk)
                    } catch (e: Exception) {

                        Log.e("LifeLogService", "persistTranscriptChunk error: ${e.message}", e)
                    } finally {
                        transcriptionBusy.set(false)
                        drainPendingTranscriptionChunk()
                    }
                }
            }
            Log.i("LifeLogService", "always-on transcription thread exited")
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopTranscriptionCapture() {
        transcriptionActive = false
        recordingThread?.interrupt()
        recordingThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        transcriptionBusy.set(false)
        pendingTranscriptionChunk = null
        Log.i("LifeLogService", "always-on transcription stopped")
        setWakeStatus("Mic", "Stopped")
    }

    private fun drainPendingTranscriptionChunk() {
        val next = pendingTranscriptionChunk ?: return
        if (!transcriptionActive) {
            pendingTranscriptionChunk = null
            return
        }
        if (!transcriptionBusy.compareAndSet(false, true)) return
        pendingTranscriptionChunk = null
        scope.launch {
            try {
                Log.d("LifeLogService", "processing queued chunk...")
                persistTranscriptChunk(next)
            } catch (e: Exception) {
                Log.e("LifeLogService", "queued persistTranscriptChunk error: ${e.message}", e)
            } finally {
                transcriptionBusy.set(false)
                if (pendingTranscriptionChunk != null) drainPendingTranscriptionChunk()
            }
        }
    }

    private fun setWakeStatus(state: String, detail: String = "") {
        sendBroadcast(Intent(WakeWordService.ACTION_WAKE_STATE).apply {
            setPackage(packageName)
            putExtra(WakeWordService.EXTRA_STATE, state)
            putExtra(WakeWordService.EXTRA_DETAIL, detail)
        })
        val text = if (detail.isNotBlank()) "Background telemetry active • $state: $detail" else "Background telemetry active • $state"
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, createNotification(text))
    }

    private suspend fun persistTranscriptChunk(pcm: ByteArray) {
        if (!whisperEngine.isReady()) {
            Log.w("LifeLogService", "Whisper model not ready")
            return
        }
        val text = whisperEngine.transcribePcm16(pcm).trim()
        if (text.isEmpty()) {
            Log.d("LifeLogService", "transcription result empty")
            return
        }
        if (!shouldPersistTranscript(text)) {
            Log.d("LifeLogService", "transcript filtered (hallucination or duplicate): '$text'")
            return
        }
        phoneLogDao.insert(
            PhoneLogEntity(
                id = System.currentTimeMillis(),
                ts = Instant.now().toString(),
                deviceId = Build.MODEL,
                kind = "transcript",
                text = text,
                transcriptId = 0L,
                sourceType = "on_device_asr",
                speakerId = "",
                speakerConfidence = 0.0,
                tags = "",
                importance = 0.8,
                mediaLikelihood = 0.0,
                dialogDensity = 0.0,
                source = "lifelog_service_mic",
            )
        )
        Log.i("LifeLogService", "transcript saved: $text")
        // Trigger UI refresh via broadcast
        sendBroadcast(Intent("com.lifelog.phone.REFRESH_CHAT").apply {
            setPackage(packageName)
            putExtra("new_message", "true")
        })
    }

    private suspend fun cleanupHistoricalTranscriptNoise() {

        val transcriptRows = phoneLogDao.getByKind("transcript", 1500)
        if (transcriptRows.isEmpty()) return
        val idsToDelete = transcriptRows
            .filter { shouldDeleteHistoricalTranscript(it.text) }
            .map { it.id }
        if (idsToDelete.isNotEmpty()) {
            phoneLogDao.deleteByIds(idsToDelete)
        }
        Log.i("LifeLogService", "historical transcript cleanup removed=${idsToDelete.size}")
    }

    private suspend fun shouldPersistTranscript(text: String): Boolean {
        if (isLikelyHallucination(text)) {
            Log.d("LifeLogService", "transcript isLikelyHallucination: '$text'")
            return false
        }
        val normalized = normalizeTranscriptText(text)
        if (normalized.isBlank()) {
            Log.d("LifeLogService", "transcript normalizeTranscriptText isBlank: '$text'")
            return false
        }
        val recent = phoneLogDao.getByKind("transcript", 5)
        val isDup = recent.any { normalizeTranscriptText(it.text) == normalized }
        if (isDup) {
            Log.d("LifeLogService", "transcript is duplicate of recent: '$text'")
            return false
        }
        return true
    }


    private fun shouldDeleteHistoricalTranscript(text: String): Boolean {
        return isLikelyHallucination(text)
    }

    private fun normalizeTranscriptText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun chunkEnergy(pcm: ByteArray): Double {
        if (pcm.size < 3200) return 0.0
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val s = (hi shl 8) or lo
            sum += if (s < 0) -s.toLong() else s.toLong()
            i += 2
        }
        return (sum.toDouble() / (pcm.size / 2)) / 32768.0
    }

    private fun isLikelyHallucination(text: String): Boolean {
        // Normalize: strip leading/trailing brackets and whitespace before tag matching
        val clean = text.trim().lowercase().replace(Regex("^[\\s\\[]+|[\\s\\]]+$"), "").trim()
        val fullClean = text.trim().lowercase()
        // Known non-speech Whisper tags
        val knownTags = setOf("music", "laughter", "noise", "inaudible", "applause", "silence", "music playing")
        if (clean in knownTags) return true
        if (knownTags.any { fullClean.contains("[$it]") }) return true
        if (Regex("\\[\\s*(music|laughter|noise|inaudible|applause|silence|music playing)\\s*\\]").containsMatchIn(fullClean)) return true

        if (fullClean.length < 4) return true
        if (!fullClean.any { it.isLetter() }) return true
        if (Regex("(.)\\1{5,}").containsMatchIn(fullClean)) return true

        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) return true
        
        val joined = tokens.joinToString(" ")
        if (joined == "the the" || joined == "and then the") return true
        if (joined.startsWith("and then the other one")) return true
        if (joined.contains("other side of the other side")) return true
        if (joined.contains("and and then the other one is the other one is")) return true

        return false
    }


    private fun startHeartbeatLoop() {
        scope.launch {
            var backoffDelay = 30_000L
            var consecutiveFailures = 0
            while (isActive) {
                val baseUrl = resolvedSyncBaseUrl()
                if (baseUrl.isBlank()) {
                    Log.d("LifeLogService", "No backend URL configured, skipping telemetry for 5min")
                    delay(300_000L)
                    continue
                }
                val result = runCatching { sendTelemetryCycle() }
                if (result.isSuccess) {
                    backoffDelay = 30_000L
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    backoffDelay = (30_000L * (1L shl (consecutiveFailures - 1))).coerceAtMost(600_000L)
                    Log.d("LifeLogService", "Telemetry failed ($consecutiveFailures×), backing off ${backoffDelay / 1000}s: ${result.exceptionOrNull()?.message}")
                }
                delay(backoffDelay)
            }
        }
    }

    private fun authHeaders(): Map<String, String> {
        val token = settingsRepository.cachedLifeLogSyncToken
            .ifBlank { settingsRepository.lifeLogSyncToken }
        return if (token.isNotEmpty()) mapOf("X-Phone-Token" to token) else emptyMap()
    }

    internal fun resolvedSyncBaseUrl(): String {
        return settingsRepository.cachedLifeLogSyncUrl
            .ifBlank { settingsRepository.lifeLogSyncUrl }
            .trim()
            .trimEnd('/')
    }

    private suspend fun sendTelemetryCycle() {
        val baseUrl = resolvedSyncBaseUrl()

        // Keep each telemetry/send step isolated so one network failure does not
        // block local reminder logic (especially dwell notifications).
        runCatching {
            postTelemetry(baseUrl, "heartbeat", com.google.gson.JsonObject().apply {
                addProperty("status", "ok")
            })
        }.onFailure { e ->
            Log.w("LifeLogService", "Heartbeat telemetry failed: ${e.message}")
        }

        buildBatteryPayload()?.let { payload ->
            runCatching { postTelemetry(baseUrl, "battery", payload) }
                .onFailure { e -> Log.w("LifeLogService", "Battery telemetry failed: ${e.message}") }
        }
        buildWifiPayload()?.let { payload ->
            runCatching { postTelemetry(baseUrl, "wifi", payload) }
                .onFailure { e -> Log.w("LifeLogService", "WiFi telemetry failed: ${e.message}") }
        }
        buildLocationPayload()?.let { payload ->
            runCatching { postTelemetry(baseUrl, "location", payload) }
                .onFailure { e -> Log.w("LifeLogService", "Location telemetry failed: ${e.message}") }
            runCatching { maybeSendLocationDwellPrompt(baseUrl, payload) }
                .onFailure { e -> Log.w("LifeLogService", "Dwell prompt check failed: ${e.message}") }
        }
        runCatching { maybeSyncCalendar(baseUrl) }
            .onFailure { e -> Log.w("LifeLogService", "Calendar sync failed: ${e.message}") }
        runCatching { maybeSendMealReminders(baseUrl) }
            .onFailure { e -> Log.w("LifeLogService", "Meal reminder check failed: ${e.message}") }
        runCatching { maybeSendMealFollowUps(baseUrl) }
            .onFailure { e -> Log.w("LifeLogService", "Meal follow-up check failed: ${e.message}") }
        runCatching { maybePollSpeakerQuestion(baseUrl) }
            .onFailure { e -> Log.w("LifeLogService", "Speaker question poll failed: ${e.message}") }
    }

    private fun postTelemetry(baseUrl: String, kind: String, payload: com.google.gson.JsonObject) {
        persistLocalPhoneLog(kind, payload)
        if (baseUrl.isBlank()) return

        val envelope = com.google.gson.JsonObject().apply {
            addProperty("device_id", android.os.Build.MODEL)
            addProperty("kind", kind)
            addProperty("ts", Instant.now().toString())
            add("data", payload)
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl/phone/telemetry")
            .post(envelope.toString().toRequestBody("application/json".toMediaType()))

        authHeaders().forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w("LifeLogService", "Telemetry $kind failed: ${resp.code}")
            }
        }
    }

    private fun buildBatteryPayload(): com.google.gson.JsonObject? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val statusRaw = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pluggedRaw = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1

        val status = when (statusRaw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val plugged = when (pluggedRaw) {
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }

        return com.google.gson.JsonObject().apply {
            if (pct >= 0) addProperty("percentage", pct)
            addProperty("status", status)
            addProperty("plugged", plugged)
        }
    }

    private fun buildWifiPayload(): com.google.gson.JsonObject? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        val links = cm.getLinkProperties(active)
        val ipv4 = links?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull()
            ?.hostAddress

        val payload = com.google.gson.JsonObject().apply {
            addProperty("network", when {
                isWifi -> "wifi"
                isCell -> "cellular"
                else -> "other"
            })
            if (!ipv4.isNullOrBlank()) addProperty("ip", ipv4)
        }

        if (isWifi && hasLocationPermission()) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val ssid = wm?.connectionInfo?.ssid?.trim()?.trim('"')
                if (!ssid.isNullOrBlank() && !ssid.equals("<unknown ssid>", ignoreCase = true)) {
                    payload.addProperty("ssid", ssid)
                }
            } catch (_: Exception) {
            }
        }
        return payload
    }

    private fun buildLocationPayload(): com.google.gson.JsonObject? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) return null
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > (best?.time ?: 0L)) best = loc
        }
        val l = best ?: return null
        val nowMs = System.currentTimeMillis()
        val fixAgeMs = (nowMs - l.time).coerceAtLeast(0L)
        return com.google.gson.JsonObject().apply {
            addProperty("latitude", l.latitude)
            addProperty("longitude", l.longitude)
            addProperty("accuracy", l.accuracy.toDouble())
            addProperty("provider", l.provider ?: "unknown")
            addProperty("fix_age_ms", fixAgeMs)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun maybeSyncCalendar(baseUrl: String) {
        if (!hasCalendarPermission()) return
        val now = System.currentTimeMillis()
        if ((now - lastCalendarSyncAtMs) < CALENDAR_SYNC_INTERVAL_MS) return
        lastCalendarSyncAtMs = now

        val events = readDeviceCalendarEvents(now)
        if (events.isEmpty()) return
        persistLocalCalendarEvents(events)

        if (baseUrl.isBlank()) {
            Log.i("LifeLogService", "Calendar mirrored locally: ${events.size} events")
            return
        }

        var synced = 0
        for (event in events) {
            val ok = lifeLogApi.upsertCalendarEvent(baseUrl, event).isSuccess
            if (ok) synced += 1
        }
        Log.i("LifeLogService", "Calendar sync complete: $synced/${events.size}")
    }

    private fun maybeSendMealReminders(baseUrl: String) {
        if (!canPostNotifications()) return
        val now = ZonedDateTime.now()
        val today = LocalDate.now().toString()
        for (reminder in MEAL_REMINDERS) {
            val target = now.withHour(reminder.hour).withMinute(reminder.minute).withSecond(0).withNano(0)
            val minutesFromTarget = Duration.between(target, now).toMinutes()
            if (minutesFromTarget !in 0..MEAL_REMINDER_WINDOW_MINUTES) continue

            val dayKey = "meal_${reminder.key}_day"
            val sentDay = reminderPrefs.getString(dayKey, "") ?: ""
            if (sentDay == today) continue

            sendMealReminderNotification(reminder, isFollowUp = false, trackPending = true)
            reminderPrefs.edit()
                .putString(dayKey, today)
                .putInt(mealFollowUpCountPrefKey(reminder.key, today), 0)
                .putLong(mealFollowUpLastPrefKey(reminder.key, today), 0L)
                .apply()
            val payload = com.google.gson.JsonObject().apply {
                addProperty("type", "meal_prompt")
                addProperty("meal", reminder.key)
                addProperty("title", reminder.title)
            }
            runCatching { postTelemetry(baseUrl, "reminder", payload) }
        }
    }

    private fun maybeSendMealFollowUps(baseUrl: String) {
        if (!canPostNotifications()) return
        val pendingMealKey = settingsRepository.cachedPendingMealKey.trim().lowercase()
        val pendingAtMs = settingsRepository.cachedPendingMealTs
        if (pendingMealKey.isBlank() || pendingAtMs <= 0L) return

        val reminder = MEAL_REMINDERS.firstOrNull { it.key == pendingMealKey }
        if (reminder == null) {
            settingsRepository.clearPendingMealPrompt()
            return
        }

        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - pendingAtMs
        if (ageMs < 0L || ageMs > MEAL_PENDING_MAX_AGE_MS) {
            settingsRepository.clearPendingMealPrompt()
            return
        }

        val today = LocalDate.now()
        val pendingDay = Instant.ofEpochMilli(pendingAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        if (pendingDay != today) {
            settingsRepository.clearPendingMealPrompt()
            return
        }

        val day = today.toString()
        val countKey = mealFollowUpCountPrefKey(pendingMealKey, day)
        val lastKey = mealFollowUpLastPrefKey(pendingMealKey, day)
        val currentCount = reminderPrefs.getInt(countKey, 0).coerceAtLeast(0)
        if (currentCount >= MEAL_MAX_FOLLOW_UPS) return

        val requiredAge = MEAL_FOLLOW_UP_INITIAL_DELAY_MS + (currentCount * MEAL_FOLLOW_UP_REPEAT_MS)
        if (ageMs < requiredAge) return

        val lastSentAt = reminderPrefs.getLong(lastKey, 0L)
        if (lastSentAt > 0L && (nowMs - lastSentAt) < MEAL_FOLLOW_UP_REPEAT_MS) return

        sendMealReminderNotification(reminder, isFollowUp = true, trackPending = false)
        val nextCount = currentCount + 1
        reminderPrefs.edit()
            .putInt(countKey, nextCount)
            .putLong(lastKey, nowMs)
            .apply()

        val payload = com.google.gson.JsonObject().apply {
            addProperty("type", "meal_prompt_followup")
            addProperty("meal", reminder.key)
            addProperty("followup_index", nextCount)
        }
        runCatching { postTelemetry(baseUrl, "reminder", payload) }
    }

    private fun maybeSendLocationDwellPrompt(baseUrl: String, payload: com.google.gson.JsonObject) {
        if (!canPostNotifications()) return
        if (!payload.has("latitude") || !payload.has("longitude")) return
        val lat = runCatching { payload.get("latitude").asDouble }.getOrNull() ?: return
        val lon = runCatching { payload.get("longitude").asDouble }.getOrNull() ?: return
        val accuracyMeters = runCatching { payload.get("accuracy").asDouble.toFloat() }.getOrNull() ?: Float.NaN
        val fixAgeMs = runCatching { payload.get("fix_age_ms").asLong }.getOrNull() ?: 0L
        if (!accuracyMeters.isNaN() && accuracyMeters > DWELL_MAX_ACCEPTED_ACCURACY_METERS) {
            dwellAnchorLat = null
            dwellAnchorLon = null
            dwellAnchorStartMs = 0L
            return
        }
        if (fixAgeMs > DWELL_MAX_FIX_AGE_MS) {
            // Ignore very stale fixes for dwell checks, but do not clear anchor.
            // Clearing causes repeated restart loops and missed 5-minute prompts.
            return
        }
        if (isKnownLocation(lat, lon)) return
        val now = System.currentTimeMillis()
        if (isDwellPromptOnCooldown(lat, lon, now)) return

        val dwellRadius = if (!accuracyMeters.isNaN()) {
            max(
                DWELL_RADIUS_METERS,
                min(DWELL_MAX_RADIUS_METERS, accuracyMeters * DWELL_ACCURACY_RADIUS_MULTIPLIER)
            )
        } else {
            DWELL_RADIUS_METERS
        }

        val anchorLat = dwellAnchorLat
        val anchorLon = dwellAnchorLon
        if (anchorLat == null || anchorLon == null) {
            dwellAnchorLat = lat
            dwellAnchorLon = lon
            dwellAnchorStartMs = now
            return
        }

        val metersFromAnchor = distanceMeters(anchorLat, anchorLon, lat, lon)
        if (metersFromAnchor > dwellRadius) {
            dwellAnchorLat = lat
            dwellAnchorLon = lon
            dwellAnchorStartMs = now
            return
        }
        if ((now - dwellAnchorStartMs) < DWELL_MIN_DURATION_MS) return
        val prevPromptLat = lastDwellPromptLat
        val prevPromptLon = lastDwellPromptLon
        if (prevPromptLat != null && prevPromptLon != null) {
            val metersFromLastPrompt = distanceMeters(prevPromptLat, prevPromptLon, lat, lon)
            if (metersFromLastPrompt < DWELL_REPEAT_DISTANCE_METERS) return
        }

        val place = reverseGeocodeLabel(lat, lon)
        sendLocationCheckInNotification(place, lat, lon)
        markDwellPrompted(lat, lon, now)
        lastDwellPromptLat = lat
        lastDwellPromptLon = lon

        val event = com.google.gson.JsonObject().apply {
            addProperty("type", "location_checkin_prompt")
            addProperty("place", place)
            addProperty("latitude", lat)
            addProperty("longitude", lon)
        }
        runCatching { postTelemetry(baseUrl, "reminder", event) }
    }

    private fun reverseGeocodeLabel(lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val rows = geocoder.getFromLocation(lat, lon, 1)
            val row = rows?.firstOrNull()
            fun pickReadable(vararg values: String?): String {
                for (raw in values) {
                    val text = raw?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    if (!text.any { it.isLetter() }) continue
                    return text
                }
                return ""
            }
            val name = pickReadable(
                row?.featureName,
                row?.thoroughfare,
                row?.subLocality,
                row?.locality,
                row?.adminArea
            )
            if (name.isNotEmpty()) name else "this location"
        } catch (_: Exception) {
            "this location"
        }
    }

    private fun sendLocationCheckInNotification(place: String, lat: Double, lon: Double) {
        ensureReminderChannel()
        val promptText = "I've noticed you've been at this location for more than 5 minutes. Are you at $place?"
        val launchIntent = Intent(this, com.lifelog.phone.presentation.MainActivity::class.java)
            .putExtra("chat_seed_assistant", promptText)
        val launchPending = PendingIntent.getActivity(
            this,
            3410,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val yesIntent = Intent(this, LifeLogService::class.java).apply {
            action = ACTION_LOCATION_CONFIRMATION
            putExtra(EXTRA_LOCATION_CONFIRMED, true)
            putExtra(EXTRA_LOCATION_PLACE, place)
            putExtra(EXTRA_LOCATION_LAT, lat)
            putExtra(EXTRA_LOCATION_LON, lon)
        }
        val yesPending = PendingIntent.getService(
            this,
            3411,
            yesIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val noIntent = Intent(this, LifeLogService::class.java).apply {
            action = ACTION_LOCATION_CONFIRMATION
            putExtra(EXTRA_LOCATION_CONFIRMED, false)
            putExtra(EXTRA_LOCATION_PLACE, place)
            putExtra(EXTRA_LOCATION_LAT, lat)
            putExtra(EXTRA_LOCATION_LON, lon)
        }
        val noPending = PendingIntent.getService(
            this,
            3412,
            noIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_LOCATION_NAME)
            .setLabel("If no, add the place name")
            .build()
        val noAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "No / Add Name",
            noPending
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("LifeLog location check")
            .setContentText(promptText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(launchPending)
            .addAction(android.R.drawable.checkbox_on_background, "Yes", yesPending)
            .addAction(noAction)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(REMINDER_NOTIFICATION_BASE + LOCATION_CHECKIN_NOTIFICATION_ID, notification)
        appendAssistantChatPrompt(promptText)
    }

    private suspend fun handleLocationConfirmation(intent: Intent) {
        val confirmed = intent.getBooleanExtra(EXTRA_LOCATION_CONFIRMED, false)
        val suggestedPlace = intent.getStringExtra(EXTRA_LOCATION_PLACE).orEmpty().trim()
        val lat = intent.getDoubleExtra(EXTRA_LOCATION_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LOCATION_LON, Double.NaN)
        val inline = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_LOCATION_NAME)
            ?.toString()
            ?.trim()
            .orEmpty()
        val finalPlace = if (inline.isNotEmpty()) inline else suggestedPlace

        // Close the interactive notification promptly so action spinner does not hang.
        runCatching {
            getSystemService(NotificationManager::class.java)
                .cancel(REMINDER_NOTIFICATION_BASE + LOCATION_CHECKIN_NOTIFICATION_ID)
        }

        val shouldRemember = finalPlace.isNotEmpty() && !finalPlace.equals("this location", ignoreCase = true)
        if (!lat.isNaN() && !lon.isNaN() && (confirmed || shouldRemember)) {
            rememberKnownLocation(lat, lon, finalPlace)
        }
        if (finalPlace.isNotEmpty()) {
            val userText = if (confirmed) {
                "Yes, I'm at $finalPlace."
            } else {
                "No, I'm at $finalPlace."
            }
            appendUserChatMessage(userText)
            appendAssistantChatPrompt("Got it. I'll use $finalPlace for this place.")
        }

        val baseUrl = resolvedSyncBaseUrl()
        if (baseUrl.isBlank()) {
            Log.w("LifeLogService", "location confirmation saved locally (no base URL)")
            return
        }
        val kind = if (confirmed) "location_confirmed" else "location_corrected"
        val payload = com.google.gson.JsonObject().apply {
            addProperty("suggested_place", suggestedPlace)
            addProperty("confirmed", confirmed)
            if (finalPlace.isNotEmpty()) addProperty("final_place", finalPlace)
            if (!lat.isNaN()) addProperty("latitude", lat)
            if (!lon.isNaN()) addProperty("longitude", lon)
        }
        runCatching { postTelemetry(baseUrl, kind, payload) }
            .onSuccess { Log.i("LifeLogService", "location confirmation posted: $kind place=$finalPlace") }
            .onFailure { e -> Log.w("LifeLogService", "location confirmation post failed: ${e.message}") }
    }

    private suspend fun handleMealReply(intent: Intent) {
        val mealKey = intent.getStringExtra(EXTRA_MEAL_KEY).orEmpty().trim().lowercase()
        val inline = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_MEAL_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (inline.isEmpty()) return

        val mealPrefix = when (mealKey) {
            "breakfast" -> "For breakfast today I had"
            "lunch" -> "For lunch today I had"
            "dinner" -> "For dinner today I had"
            else -> "Meal note:"
        }
        val userText = "$mealPrefix $inline"
        appendUserChatMessage(userText)
        appendAssistantChatPrompt("Saved. Thanks for the update.")

        val baseUrl = resolvedSyncBaseUrl()
        if (baseUrl.isNotBlank()) {
            val day = LocalDate.now().toString()
            lifeLogApi.submitMealResponse(
                baseUrl = baseUrl,
                mealKey = mealKey.ifBlank { "meal" },
                answer = inline,
                day = day
            ).onFailure { e ->
                Log.w("LifeLogService", "meal response save failed: ${e.message}")
            }
            val payload = com.google.gson.JsonObject().apply {
                addProperty("type", "meal_reply")
                addProperty("meal", mealKey)
                addProperty("text", inline)
            }
            runCatching { postTelemetry(baseUrl, "meal_response", payload) }
        }
        settingsRepository.clearPendingMealPrompt()
    }

    private fun mealFollowUpCountPrefKey(mealKey: String, day: String): String =
        "meal_${mealKey}_${day}_followup_count"

    private fun mealFollowUpLastPrefKey(mealKey: String, day: String): String =
        "meal_${mealKey}_${day}_followup_last_ms"

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out.firstOrNull() ?: Float.MAX_VALUE
    }

    private fun isKnownLocation(lat: Double, lon: Double): Boolean {
        val known = loadKnownLocations()
        if (known.isEmpty()) return false
        return known.any { entry ->
            distanceMeters(entry.lat, entry.lon, lat, lon) <= KNOWN_LOCATION_RADIUS_METERS
        }
    }

    private fun rememberKnownLocation(lat: Double, lon: Double, name: String) {
        if (name.isBlank()) return
        val known = loadKnownLocations().toMutableList()
        val idx = known.indexOfFirst { entry ->
            distanceMeters(entry.lat, entry.lon, lat, lon) <= KNOWN_LOCATION_RADIUS_METERS
        }
        val now = System.currentTimeMillis()
        val updated = KnownLocationEntry(lat = lat, lon = lon, name = name.trim(), updatedAt = now)
        if (idx >= 0) {
            known[idx] = updated
        } else {
            known.add(updated)
        }
        val compact = known
            .sortedByDescending { it.updatedAt }
            .take(MAX_KNOWN_LOCATIONS)
        saveKnownLocations(compact)
    }

    private fun loadKnownLocations(): List<KnownLocationEntry> {
        val raw = reminderPrefs.getString(KNOWN_LOCATIONS_PREF_KEY, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val lat = if (obj.has("lat")) obj.optDouble("lat", Double.NaN) else Double.NaN
                    val lon = if (obj.has("lon")) obj.optDouble("lon", Double.NaN) else Double.NaN
                    val name = obj.optString("name", "").trim()
                    if (lat.isNaN() || lon.isNaN() || name.isEmpty()) continue
                    add(
                        KnownLocationEntry(
                            lat = lat,
                            lon = lon,
                            name = name,
                            updatedAt = obj.optLong("updated_at", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveKnownLocations(entries: List<KnownLocationEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject()
                    .put("lat", entry.lat)
                    .put("lon", entry.lon)
                    .put("name", entry.name)
                    .put("updated_at", entry.updatedAt)
            )
        }
        reminderPrefs.edit().putString(KNOWN_LOCATIONS_PREF_KEY, arr.toString()).apply()
    }

    private fun bucketFor(lat: Double, lon: Double): Pair<Int, Int> {
        val latBucket = floor(lat * DWELL_CELL_SCALE).toInt()
        val lonBucket = floor(lon * DWELL_CELL_SCALE).toInt()
        return latBucket to lonBucket
    }

    private fun bucketKey(latBucket: Int, lonBucket: Int): String = "$latBucket:$lonBucket"

    private fun isDwellPromptOnCooldown(lat: Double, lon: Double, nowMs: Long): Boolean {
        val raw = reminderPrefs.getString(DWELL_PROMPT_CELL_PREF_KEY, "{}").orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val (latBucket, lonBucket) = bucketFor(lat, lon)
        for (dLat in -1..1) {
            for (dLon in -1..1) {
                val key = bucketKey(latBucket + dLat, lonBucket + dLon)
                val ts = obj.optLong(key, 0L)
                if (ts > 0L && (nowMs - ts) in 0..DWELL_CELL_COOLDOWN_MS) {
                    return true
                }
            }
        }
        return false
    }

    private fun markDwellPrompted(lat: Double, lon: Double, nowMs: Long) {
        val raw = reminderPrefs.getString(DWELL_PROMPT_CELL_PREF_KEY, "{}").orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val keysToDrop = mutableListOf<String>()
        val iter = obj.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            val ts = obj.optLong(key, 0L)
            if (ts <= 0L || (nowMs - ts) > DWELL_CELL_RETENTION_MS) {
                keysToDrop.add(key)
            }
        }
        for (key in keysToDrop) {
            obj.remove(key)
        }
        val (latBucket, lonBucket) = bucketFor(lat, lon)
        obj.put(bucketKey(latBucket, lonBucket), nowMs)
        reminderPrefs.edit().putString(DWELL_PROMPT_CELL_PREF_KEY, obj.toString()).apply()
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    private fun sendMealReminderNotification(
        reminder: MealReminder,
        isFollowUp: Boolean,
        trackPending: Boolean
    ) {
        ensureReminderChannel()
        val contentText = if (isFollowUp) {
            "Quick reminder: ${reminder.text}"
        } else {
            reminder.text
        }
        val contentTitle = if (isFollowUp) {
            "${reminder.title} (reminder)"
        } else {
            reminder.title
        }
        val launchIntent = Intent(this, com.lifelog.phone.presentation.MainActivity::class.java)
            .putExtra("chat_seed_assistant", contentText)
        val pendingIntent = PendingIntent.getActivity(
            this,
            100 + reminder.notificationId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyIntent = Intent(this, LifeLogService::class.java).apply {
            action = ACTION_MEAL_REPLY
            putExtra(EXTRA_MEAL_KEY, reminder.key)
        }
        val replyPendingIntent = PendingIntent.getService(
            this,
            500 + reminder.notificationId,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mealRemoteInput = RemoteInput.Builder(REMOTE_INPUT_MEAL_TEXT)
            .setLabel("Type your answer")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(mealRemoteInput).build()
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(REMINDER_NOTIFICATION_BASE + reminder.notificationId, notification)
        if (trackPending) {
            settingsRepository.setPendingMealPrompt(reminder.key)
        }
        appendAssistantChatPrompt(contentText)
    }

    private fun ensureReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "LifeLog Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Meal and check-in reminders"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun appendAssistantChatPrompt(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            runCatching {
                messageRepository.insertIfNotRecent(
                    role = "assistant",
                    text = clean,
                    windowMs = 180_000L
                )
            }
                .onFailure { e -> Log.w("LifeLogService", "assistant chat prompt insert failed: ${e.message}") }
        }
    }

    private fun appendUserChatMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            runCatching {
                messageRepository.insertIfNotRecent(
                    role = "user",
                    text = clean,
                    windowMs = 60_000L
                )
            }
                .onFailure { e -> Log.w("LifeLogService", "user chat insert failed: ${e.message}") }
        }
    }

    private fun persistLocalPhoneLog(kind: String, payload: com.google.gson.JsonObject) {
        val summary = summarizePayload(kind, payload)
        if (summary.isBlank()) return
        scope.launch {
            runCatching {
                phoneLogDao.insert(
                    PhoneLogEntity(
                        id = System.currentTimeMillis(),
                        ts = Instant.now().toString(),
                        deviceId = android.os.Build.MODEL,
                        kind = kind,
                        text = summary,
                        transcriptId = 0L,
                        sourceType = "phone_local",
                        speakerId = "",
                        speakerConfidence = 0.0,
                        tags = "",
                        importance = 0.4,
                        mediaLikelihood = 0.0,
                        dialogDensity = 0.0,
                        source = "phone"
                    )
                )
            }.onFailure { e ->
                Log.w("LifeLogService", "local phone log insert failed: ${e.message}")
            }
        }
    }

    private fun summarizePayload(kind: String, payload: com.google.gson.JsonObject): String {
        fun stringValue(key: String): String = runCatching { payload.get(key).asString }.getOrDefault("").trim()
        fun doubleValue(key: String): Double? = runCatching { payload.get(key).asDouble }.getOrNull()
        fun longValue(key: String): Long? = runCatching { payload.get(key).asLong }.getOrNull()

        return when (kind) {
            "battery" -> {
                val pct = longValue("percentage")?.toString().orEmpty()
                val status = stringValue("status")
                val plugged = stringValue("plugged")
                listOf("Battery", pct.takeIf { it.isNotBlank() }?.let { "$it%" }, status, plugged)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            "wifi" -> {
                val network = stringValue("network")
                val ssid = stringValue("ssid")
                val ip = stringValue("ip")
                listOf("Network", network, ssid.takeIf { it.isNotBlank() }?.let { "SSID $it" }, ip.takeIf { it.isNotBlank() }?.let { "IP $it" })
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
            }
            "location" -> {
                val lat = doubleValue("latitude")
                val lon = doubleValue("longitude")
                val accuracy = doubleValue("accuracy")
                buildString {
                    append("Location")
                    if (lat != null && lon != null) append(" $lat,$lon")
                    if (accuracy != null) append(" accuracy ${accuracy.toInt()}m")
                }
            }
            "heartbeat" -> "Heartbeat ok"
            "reminder" -> payload.toString()
            "meal_response" -> payload.toString()
            "location_confirmed", "location_corrected" -> payload.toString()
            else -> payload.toString()
        }
    }

    private fun persistLocalCalendarEvents(events: List<CalendarSyncEvent>) {
        scope.launch {
            runCatching {
                calendarEventDao.clearAll()
                calendarEventDao.insertAll(
                    events.mapIndexed { index, event ->
                        CalendarEventEntity(
                            id = index.toLong() + 1L,
                            source = event.source,
                            remoteId = event.remoteId,
                            calendarId = event.calendarId,
                            title = event.title,
                            startTs = event.startTs,
                            endTs = event.endTs,
                            timezone = event.timezone,
                            recurrenceRule = event.recurrenceRule,
                            location = event.location,
                            notes = event.notes,
                            isAllDay = event.isAllDay,
                            status = event.status,
                            updatedAt = Instant.now().toString(),
                            display = listOf(event.title, event.location, event.notes)
                                .filter { it.isNotBlank() }
                                .joinToString(" | ")
                        )
                    }
                )
            }.onFailure { e ->
                Log.w("LifeLogService", "local calendar mirror failed: ${e.message}")
            }
        }
    }

    private fun readDeviceCalendarEvents(nowMs: Long): List<CalendarSyncEvent> {
        if (!hasCalendarPermission()) return emptyList()

        val startWindow = nowMs - 24L * 60L * 60L * 1000L
        val endWindow = nowMs + 45L * 24L * 60L * 60L * 1000L
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.VISIBLE,
        )
        val selection = (
            "${CalendarContract.Events.DELETED}=0 AND ${CalendarContract.Events.VISIBLE}=1 AND (" +
                "((${CalendarContract.Events.DTSTART} BETWEEN ? AND ?) OR (${CalendarContract.Events.DTEND} BETWEEN ? AND ?)) " +
                "OR ${CalendarContract.Events.RRULE} IS NOT NULL)"
            )
        val args = arrayOf(
            startWindow.toString(),
            endWindow.toString(),
            startWindow.toString(),
            endWindow.toString(),
        )

        val out = ArrayList<CalendarSyncEvent>(128)
        val cursor = runCatching {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} ASC"
            )
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val idxId = c.getColumnIndex(CalendarContract.Events._ID)
            val idxCalendarId = c.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val idxTitle = c.getColumnIndex(CalendarContract.Events.TITLE)
            val idxStart = c.getColumnIndex(CalendarContract.Events.DTSTART)
            val idxEnd = c.getColumnIndex(CalendarContract.Events.DTEND)
            val idxTz = c.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)
            val idxRrule = c.getColumnIndex(CalendarContract.Events.RRULE)
            val idxLocation = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val idxDescription = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val idxAllDay = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val idxStatus = c.getColumnIndex(CalendarContract.Events.STATUS)

            while (c.moveToNext()) {
                val eventId = if (idxId >= 0) c.getLong(idxId) else 0L
                val title = if (idxTitle >= 0) c.getString(idxTitle).orEmpty().trim() else ""
                if (eventId <= 0L || title.isEmpty()) continue
                val startMs = if (idxStart >= 0) c.getLong(idxStart) else 0L
                if (startMs <= 0L) continue
                val endMs = if (idxEnd >= 0) c.getLong(idxEnd) else startMs
                val isAllDay = (if (idxAllDay >= 0) c.getInt(idxAllDay) else 0) == 1
                val startTs = if (isAllDay) {
                    millisToAllDayUtcDateTs(startMs)
                } else {
                    millisToLocalTs(startMs)
                }
                val endTs = if (isAllDay) {
                    val endInclusiveMs = when {
                        endMs > startMs -> endMs - 1L
                        else -> startMs
                    }
                    millisToAllDayUtcDateTs(endInclusiveMs)
                } else {
                    millisToLocalTs(if (endMs > 0L) endMs else startMs)
                }
                if (startTs.isEmpty()) continue

                val status = when (if (idxStatus >= 0) c.getInt(idxStatus) else CalendarContract.Events.STATUS_CONFIRMED) {
                    CalendarContract.Events.STATUS_CANCELED -> "cancelled"
                    CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
                    else -> "confirmed"
                }

                out.add(
                    CalendarSyncEvent(
                        source = "local",
                        remoteId = "android_event_$eventId",
                        calendarId = if (idxCalendarId >= 0) c.getLong(idxCalendarId).toString() else "",
                        title = title,
                        startTs = startTs,
                        endTs = endTs.ifBlank { startTs },
                        timezone = if (idxTz >= 0) c.getString(idxTz).orEmpty() else "",
                        recurrenceRule = if (idxRrule >= 0) c.getString(idxRrule).orEmpty() else "",
                        location = if (idxLocation >= 0) c.getString(idxLocation).orEmpty() else "",
                        notes = if (idxDescription >= 0) c.getString(idxDescription).orEmpty() else "",
                        isAllDay = isAllDay,
                        status = status,
                        syncGoogle = false,
                    )
                )
                if (out.size >= MAX_CALENDAR_EVENTS_PER_SYNC) break
            }
        }
        return out
    }

    private fun millisToLocalTs(ms: Long): String {
        return try {
            Instant.ofEpochMilli(ms)
                .atZone(ZoneId.systemDefault())
                .format(TS_FORMAT)
        } catch (_: Exception) {
            ""
        }
    }

    private fun millisToAllDayUtcDateTs(ms: Long): String {
        return try {
            val day = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
            "${day} 00:00:00"
        } catch (_: Exception) {
            ""
        }
    }

    private fun createNotification(contentText: String = "Background telemetry active"): Notification {
        val channelId = "lifelog_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LifeLog Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, com.lifelog.phone.presentation.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("LifeLog")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private data class MealReminder(
        val key: String,
        val hour: Int,
        val minute: Int,
        val title: String,
        val text: String,
        val notificationId: Int
    )

    private data class KnownLocationEntry(
        val lat: Double,
        val lon: Double,
        val name: String,
        val updatedAt: Long
    )

    // ── Speaker identification ──────────────────────────────────────────────

    private suspend fun maybePollSpeakerQuestion(baseUrl: String) {
        if (baseUrl.isBlank()) return
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/phone/speaker/pending")
            .get()
        authHeaders().forEach { (k, v) -> req.addHeader(k, v) }
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(req.build()).execute().use { it.body?.string() }
            } catch (e: Exception) {
                Log.w("LifeLogService", "speaker/pending fetch error: ${e.message}")
                null
            }
        } ?: return
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return
        if (!json.optBoolean("ok", false)) return
        val pending = json.optJSONObject("pending") ?: return
        val tempId = pending.optString("temp_id", "").trim()
        val clusterSize = pending.optInt("cluster_size", 0)
        val sampleTs = pending.optString("sample_ts", "").trim()
        if (tempId.isEmpty()) return
        if (canPostNotifications()) {
            sendSpeakerQuestionNotification(tempId, clusterSize, sampleTs)
        }
    }

    private fun sendSpeakerQuestionNotification(tempId: String, clusterSize: Int, sampleTs: String) {
        ensureReminderChannel()
        val bodyText = if (clusterSize > 0)
            "Heard an unrecognised voice in $clusterSize recording${if (clusterSize != 1) "s" else ""}. Who is this?"
        else
            "Heard an unrecognised voice. Who is this?"

        val replyIntent = Intent(this, LifeLogService::class.java).apply {
            action = ACTION_SPEAKER_REPLY
            putExtra(EXTRA_SPEAKER_TEMP_ID, tempId)
        }
        val replyPending = PendingIntent.getService(
            this, 3420, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val skipIntent = Intent(this, LifeLogService::class.java).apply {
            action = ACTION_SPEAKER_SKIP
            putExtra(EXTRA_SPEAKER_TEMP_ID, tempId)
        }
        val skipPending = PendingIntent.getService(
            this, 3421, skipIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_SPEAKER_NAME)
            .setLabel("Enter their name…")
            .build()
        val nameAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "Name this person",
            replyPending
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("LifeLog — new voice detected")
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(nameAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skipPending)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(REMINDER_NOTIFICATION_BASE + SPEAKER_QUESTION_NOTIFICATION_ID, notification)
    }

    private suspend fun handleSpeakerReply(intent: Intent) {
        val tempId = intent.getStringExtra(EXTRA_SPEAKER_TEMP_ID).orEmpty().trim()
        val name = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_SPEAKER_NAME)
            ?.toString()?.trim().orEmpty()
        if (tempId.isEmpty() || name.isEmpty()) return

        val baseUrl = resolvedSyncBaseUrl()
        if (baseUrl.isEmpty()) return

        val payload = JSONObject().apply {
            put("temp_id", tempId)
            put("name", name)
        }
        val req = Request.Builder()
            .url("$baseUrl/phone/speaker/identify")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        authHeaders().forEach { (k, v) -> req.addHeader(k, v) }

        withContext(Dispatchers.IO) {
            try {
                client.newCall(req.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.i("LifeLogService", "Speaker '$name' identified for $tempId")
                    } else {
                        Log.w("LifeLogService", "speaker/identify failed: ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w("LifeLogService", "speaker/identify error: ${e.message}")
            }
        }
        // Dismiss the notification
        getSystemService(NotificationManager::class.java)
            .cancel(REMINDER_NOTIFICATION_BASE + SPEAKER_QUESTION_NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TRANSCRIPTION_START = "com.lifelog.phone.action.TRANSCRIPTION_START"
        const val ACTION_TRANSCRIPTION_STOP = "com.lifelog.phone.action.TRANSCRIPTION_STOP"
        private const val KEY_TRANSCRIPT_CLEANUP_AT_MS = "transcript_cleanup_at_ms"
        private const val CALENDAR_SYNC_INTERVAL_MS = 15L * 60L * 1000L
        private const val TRANSCRIPT_CLEANUP_INTERVAL_MS = 12L * 60L * 60L * 1000L
        private const val TRANSCRIPTION_SAMPLE_RATE = 16_000
        private const val TRANSCRIPTION_CHUNK_MS = 4_000L  // shorter chunks reduce UI latency and missed speech
        private const val TRANSCRIPTION_CHUNK_SAMPLES = (TRANSCRIPTION_SAMPLE_RATE * TRANSCRIPTION_CHUNK_MS / 1000).toInt()
        private const val TRANSCRIPTION_CHUNK_BYTES = TRANSCRIPTION_CHUNK_SAMPLES * 2
        private const val TRANSCRIPTION_VAD_THRESHOLD = 0.0012
        private const val MAX_CALENDAR_EVENTS_PER_SYNC = 200


        private const val REMINDER_CHANNEL_ID = "lifelog_meal_reminders"
        private const val REMINDER_NOTIFICATION_BASE = 2200
        private const val MEAL_REMINDER_WINDOW_MINUTES = 25L
        private const val LOCATION_CHECKIN_NOTIFICATION_ID = 50
        private const val ACTION_LOCATION_CONFIRMATION = "com.lifelog.phone.action.LOCATION_CONFIRMATION"
        private const val ACTION_LOCATION_TEST_PROMPT = "com.lifelog.phone.action.LOCATION_TEST_PROMPT"
        private const val ACTION_MEAL_TEST_PROMPT = "com.lifelog.phone.action.MEAL_TEST_PROMPT"
        private const val ACTION_MEAL_REPLY = "com.lifelog.phone.action.MEAL_REPLY"
        private const val EXTRA_MEAL_KEY = "extra_meal_key"
        private const val REMOTE_INPUT_MEAL_TEXT = "meal_reply_text"
        private const val REMOTE_INPUT_LOCATION_NAME = "location_name"
        private const val ACTION_SPEAKER_REPLY = "com.lifelog.phone.action.SPEAKER_REPLY"
        private const val ACTION_SPEAKER_SKIP = "com.lifelog.phone.action.SPEAKER_SKIP"
        private const val EXTRA_SPEAKER_TEMP_ID = "extra_speaker_temp_id"
        private const val REMOTE_INPUT_SPEAKER_NAME = "speaker_name"
        private const val SPEAKER_QUESTION_NOTIFICATION_ID = 55
        private const val EXTRA_LOCATION_CONFIRMED = "extra_location_confirmed"
        private const val EXTRA_LOCATION_PLACE = "extra_location_place"
        private const val EXTRA_LOCATION_LAT = "extra_location_lat"
        private const val EXTRA_LOCATION_LON = "extra_location_lon"
        private const val KNOWN_LOCATIONS_PREF_KEY = "known_locations_v1"
        private const val DWELL_PROMPT_CELL_PREF_KEY = "dwell_prompt_cells_v2"
        private const val DWELL_RADIUS_METERS = 120f
        private const val DWELL_MAX_RADIUS_METERS = 240f
        private const val DWELL_ACCURACY_RADIUS_MULTIPLIER = 1.3f
        private const val DWELL_REPEAT_DISTANCE_METERS = 350f
        private const val DWELL_MIN_DURATION_MS = 5L * 60L * 1000L
        private const val KNOWN_LOCATION_RADIUS_METERS = 260f
        private const val DWELL_CELL_SCALE = 1000.0
        private const val DWELL_CELL_COOLDOWN_MS = 8L * 60L * 60L * 1000L
        private const val DWELL_CELL_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private const val DWELL_MAX_ACCEPTED_ACCURACY_METERS = 220f
        private const val DWELL_MAX_FIX_AGE_MS = 15L * 60L * 1000L
        private const val MEAL_PENDING_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        private const val MEAL_FOLLOW_UP_INITIAL_DELAY_MS = 45L * 60L * 1000L
        private const val MEAL_FOLLOW_UP_REPEAT_MS = 45L * 60L * 1000L
        private const val MEAL_MAX_FOLLOW_UPS = 2
        private const val MAX_KNOWN_LOCATIONS = 400
        private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val MEAL_REMINDERS = listOf(
            MealReminder(
                key = "breakfast",
                hour = 10,
                minute = 0,
                title = "LifeLog breakfast check-in",
                text = "What did you have for breakfast today?",
                notificationId = 1
            ),
            MealReminder(
                key = "lunch",
                hour = 13,
                minute = 0,
                title = "LifeLog lunch check-in",
                text = "What did you have for lunch today?",
                notificationId = 2
            ),
            MealReminder(
                key = "dinner",
                hour = 19,
                minute = 0,
                title = "LifeLog dinner check-in",
                text = "What did you have for dinner today?",
                notificationId = 3
            )
        )
    }
}
