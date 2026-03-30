package com.lifelog.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.IBinder
import android.os.BatteryManager
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Process
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException

class LifeLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var api: Api

    @Volatile private var loopsStarted: Boolean = false

    @Volatile private var lastOutboxId: Long = -1L
    @Volatile private var ttsReady: Boolean = false
    private var tts: TextToSpeech? = null
    private var locMgr: LocationManager? = null
    @Volatile private var lastLoc: Location? = null
    @Volatile private var locRequested: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    @Volatile private var wakewordRunning: Boolean = false
    @Volatile private var wakewordInFlight: Boolean = false
    @Volatile private var followUpRequested: Boolean = false

    // TrafficStats deltas: store previous snapshot for computing per-interval deltas.
    @Volatile private var prevTotalRx: Long = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var prevTotalTx: Long = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var prevMobileRx: Long = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var prevMobileTx: Long = TrafficStats.UNSUPPORTED.toLong()

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLoc = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
        override fun onProviderEnabled(provider: String) { }
        override fun onProviderDisabled(provider: String) { }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        ensureChannel()
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifelog:phone").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) { }
        }

        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                try {
                    tts?.language = Locale.US
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { }
                        override fun onDone(utteranceId: String?) { }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { }
                    })
                } catch (_: Exception) { }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotif("Running (mic)")
        if (Build.VERSION.SDK_INT >= 29) {
            val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(
                NOTIF_ID,
                notif,
                types
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (!loopsStarted) {
            loopsStarted = true
            scope.launch { outboxLoop() }
            scope.launch { telemetryLoop() }
            scope.launch { loggerUploadLoop() }
            scope.launch { wakewordLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakewordRunning = false
        try {
            locMgr?.removeUpdates(locListener)
        } catch (_: Exception) { }
        locRequested = false
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        try { tone.release() } catch (_: Exception) { }
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, "LifeLog", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
    }

    private fun buildNotif(line: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LifeLog")
            .setContentText(line)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun bumpMediaVolumeMin() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            val cur = am.getStreamVolume(stream)
            if (cur <= 1) {
                am.setStreamVolume(stream, 8, 0)
            }
        } catch (_: Exception) { }
    }

    private suspend fun speakAndWait(text: String): Boolean {
        bumpMediaVolumeMin()
        if (!ttsReady) {
            Log.w("LifeLogService", "tts not ready; cannot speak yet")
            return false
        }
        val t = tts ?: run {
            Log.w("LifeLogService", "tts instance missing; cannot speak")
            return false
        }
        val utterId = "lifelog_${System.currentTimeMillis()}"
        val done = AtomicBoolean(false)
        try {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { }
                override fun onDone(utteranceId: String?) { done.set(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.set(true) }
            })
            t.speak(text.take(1200), TextToSpeech.QUEUE_FLUSH, null, utterId)
        } catch (_: Exception) {
            Log.w("LifeLogService", "tts speak() failed")
            return false
        }
        // Wait up to ~25s.
        repeat(50) {
            if (done.get()) return true
            delay(500)
        }
        Log.w("LifeLogService", "tts timed out waiting for completion")
        return true
    }

    private suspend fun outboxLoop() {
        while (scope.isActive) {
            try {
                if (!ttsReady) {
                    delay(400)
                    continue
                }
                val msg = api.outboxNext()
                if (msg == null) {
                    delay(800)
                    continue
                }
                val id = msg.optLong("id", -1L)
                val text = msg.optString("text", "").trim()
                if (id <= 0 || text.isEmpty() || id == lastOutboxId) {
                    delay(400)
                    continue
                }
                Log.i("LifeLogService", "outbox msg id=$id chars=${text.length}")
                // Speak first; ack only after we attempted playback.
                val spoke = speakAndWait(text)
                if (spoke) {
                    api.outboxAck(id)
                    lastOutboxId = id
                    Log.i("LifeLogService", "outbox ack id=$id")
                    // If the reply ends with a question, request follow-up listening
                    if (text.trimEnd().endsWith("?")) {
                        followUpRequested = true
                        Log.i("LifeLogService", "follow-up listen requested (reply was a question)")
                    }
                } else {
                    Log.w("LifeLogService", "outbox not acked (tts not ready) id=$id")
                    delay(600)
                    continue
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotif("Last: ${text.take(60)}"))
            } catch (_: Exception) {
                delay(1200)
            }
        }
    }

    private fun wifiIpString(ipInt: Int): String {
        try {
            val b = byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte(),
            )
            return InetAddress.getByAddress(b).hostAddress ?: ""
        } catch (_: Exception) { }
        return ""
    }

    private fun snapshotBatteryJson(): org.json.JSONObject {
        val res = org.json.JSONObject()
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100.0 / scale) else -1.0
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            res.put("percentage", if (pct >= 0) pct else org.json.JSONObject.NULL)
            res.put(
                "status",
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                }
            )
            res.put(
                "plugged",
                when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    0 -> "unplugged"
                    else -> "unknown"
                }
            )
        }
        return res
    }

    private fun snapshotWifiJson(): org.json.JSONObject {
        val res = org.json.JSONObject()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            res.put("transport", if (isWifi) "wifi" else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "cellular" else "other")
        } catch (_: Exception) { }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ssid = (info?.ssid ?: "").trim().trim('"')
            // Without location permission or location services enabled, many devices return "<unknown ssid>".
            if (ssid.isNotEmpty() && ssid.lowercase() != "<unknown ssid>") {
                res.put("ssid", ssid)
            }
            val rssi = info?.rssi
            if (rssi != null && rssi != -127) res.put("rssi", rssi)
            val ip = info?.ipAddress ?: 0
            val ipS = if (ip != 0) wifiIpString(ip) else ""
            if (ipS.isNotEmpty()) res.put("ip", ipS)
        } catch (_: Exception) { }

        return res
    }

    private fun ensureLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        val mgr = locMgr ?: return
        try {
            // Best effort: keep a last known location for periodic telemetry.
            val gps = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastLoc = gps ?: net ?: lastLoc
        } catch (_: Exception) { }

        if (locRequested) return
        // requestLocationUpdates() needs a Looper/Executor; telemetryLoop runs on Dispatchers.IO.
        // Use the main looper to ensure callbacks actually arrive.
        var ok = false
        try {
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 25f, locListener, Looper.getMainLooper())
            ok = true
        } catch (e: Exception) {
            Log.w("LifeLogService", "requestLocationUpdates GPS failed: ${e.message}")
        }
        try {
            mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 25f, locListener, Looper.getMainLooper())
            ok = true
        } catch (e: Exception) {
            Log.w("LifeLogService", "requestLocationUpdates NET failed: ${e.message}")
        }
        if (ok) locRequested = true
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun snapshotAppUsageJson(): org.json.JSONObject? {
        if (!hasUsageStatsPermission()) return null
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 120_000, now)
        if (stats.isNullOrEmpty()) return null

        val apps = org.json.JSONArray()
        for (s in stats) {
            val fg = s.totalTimeInForeground
            if (fg <= 0) continue
            val pkg = s.packageName ?: continue
            // Filter system packages (no dot after first segment).
            if (!pkg.contains(".")) continue
            apps.put(org.json.JSONObject().put("pkg", pkg).put("fg_ms", fg))
        }
        if (apps.length() == 0) return null
        return org.json.JSONObject().put("apps", apps)
    }

    private fun snapshotTrafficJson(): org.json.JSONObject? {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) return null

        // First call: store baseline, skip sending.
        if (prevTotalRx == TrafficStats.UNSUPPORTED.toLong()) {
            prevTotalRx = totalRx; prevTotalTx = totalTx
            prevMobileRx = mobileRx; prevMobileTx = mobileTx
            return null
        }

        val dRx = totalRx - prevTotalRx
        val dTx = totalTx - prevTotalTx
        val dMRx = mobileRx - prevMobileRx
        val dMTx = mobileTx - prevMobileTx
        prevTotalRx = totalRx; prevTotalTx = totalTx
        prevMobileRx = mobileRx; prevMobileTx = mobileTx

        return org.json.JSONObject()
            .put("total_rx_mb", dRx / 1_048_576.0)
            .put("total_tx_mb", dTx / 1_048_576.0)
            .put("mobile_rx_mb", dMRx / 1_048_576.0)
            .put("mobile_tx_mb", dMTx / 1_048_576.0)
    }

    private suspend fun telemetryLoop() {
        // Keep it modest to reduce battery impact. This is enough to build a usable
        // "where was I today?" timeline and battery/WiFi evidence.
        while (scope.isActive) {
            try {
                ensureLocationUpdates()

                val bat = snapshotBatteryJson()
                api.telemetry("battery", bat)

                val wifi = snapshotWifiJson()
                api.telemetry("wifi", wifi)

                val loc = lastLoc
                if (loc != null) {
                    val obj = org.json.JSONObject()
                        .put("lat", loc.latitude)
                        .put("lon", loc.longitude)
                        .put("accuracy_m", loc.accuracy.toDouble())
                        .put("provider", loc.provider ?: "")
                    api.telemetry("location", obj)
                }

                // App usage telemetry.
                val appUsage = snapshotAppUsageJson()
                if (appUsage != null) {
                    api.telemetry("app_usage", appUsage)
                }

                // Bandwidth / traffic telemetry.
                val traffic = snapshotTrafficJson()
                if (traffic != null) {
                    api.telemetry("traffic", traffic)
                }
            } catch (e: Exception) {
                Log.w("LifeLogService", "telemetryLoop error: ${e.message}")
            }
            delay(60_000)
        }
    }

    private fun playBeep() {
        try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110) } catch (_: Exception) { }
    }

    private fun playEndBeep() {
        try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 140) } catch (_: Exception) { }
    }

    private fun assetExists(name: String): Boolean {
        return try {
            assets.open(name).close()
            true
        } catch (_: Exception) { false }
    }

    private fun ensureAssetFile(name: String): String {
        val outFile = java.io.File(filesDir, name)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        assets.open(name).use { input ->
            java.io.FileOutputStream(outFile).use { output ->
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

    private fun chunksDir(): File {
        val d = File(cacheDir, "lifelog_chunks")
        if (!d.exists()) {
            try { d.mkdirs() } catch (_: Exception) { }
        }
        return d
    }

    private fun enqueueChunkWav(pcm: ShortArray, sampleRate: Int) {
        val dir = chunksDir()
        // Keep filenames sortable by time.
        val name = "LOG_${System.currentTimeMillis()}.wav"
        val out = File(dir, name)
        try {
            out.writeBytes(toWav(pcm, sampleRate))
            try {
                Log.i("LifeLogService", "logger chunk queued: ${out.name} bytes=${out.length()}")
            } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.w("LifeLogService", "enqueueChunkWav failed: ${e.message}")
        }
        // Avoid unbounded growth if offline: cap queued chunks.
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return
            if (files.size <= 12) return
            files.sortedBy { it.name }.take(files.size - 12).forEach { f ->
                try { f.delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private suspend fun loggerUploadLoop() {
        var backoffMs = 2000L
        while (scope.isActive) {
            if (!prefs.loggerEnabled()) {
                delay(1500)
                continue
            }
            val dir = chunksDir()
            val files: List<File> = try {
                (dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.toList() ?: emptyList())
                    .sortedBy { it.name }
            } catch (_: Exception) {
                emptyList()
            }
            if (files.isEmpty()) {
                backoffMs = 2000L
                delay(900)
                continue
            }
            val f = files[0]
            val ok = try {
                val bytes = f.readBytes()
                if (bytes.isNotEmpty()) api.uploadLoggerWav(bytes) else false
            } catch (_: Exception) {
                false
            }
            if (ok) {
                try { f.delete() } catch (_: Exception) { }
                Log.i("LifeLogService", "logger upload ok: ${f.name}")
                backoffMs = 2000L
                delay(100)
            } else {
                Log.w("LifeLogService", "logger upload failed: ${f.name} backoff_ms=$backoffMs")
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 60_000L)
            }
        }
    }

    private fun rms(buf: ShortArray, start: Int, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[start + i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / len)
    }

    private suspend fun wakewordLoop() {
        val wakeEnabled = prefs.wakewordEnabled()
        val logEnabled = prefs.loggerEnabled()
        if (!wakeEnabled && !logEnabled) return

        val accessKey = prefs.picovoiceAccessKey().trim()
        if (wakeEnabled && accessKey.isEmpty()) {
            Log.w("LifeLogService", "wakeword enabled but no Picovoice key set")
            return
        }
        val micPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
        if (micPerm != PackageManager.PERMISSION_GRANTED) {
            Log.w("LifeLogService", "mic loop enabled but RECORD_AUDIO not granted")
            return
        }

        // AudioRecord can fail transiently (audio policy changes, focus issues, OEM bugs).
        // Keep retrying so "minimize app" doesn't permanently kill the wake pipeline.
        while (scope.isActive) {
            var porcupine: Porcupine? = null
            var rec: AudioRecord? = null
            try {
                val pp: Porcupine? =
                    if (wakeEnabled) {
                        try {
                            val b = Porcupine.Builder()
                                .setAccessKey(accessKey)
                                .setSensitivity(0.50f)
                            if (assetExists("lifelog.ppn")) {
                                b.setKeywordPath(ensureAssetFile("lifelog.ppn"))
                                Log.i("LifeLogService", "wakeword: lifelog.ppn")
                            } else {
                                b.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                                Log.w("LifeLogService", "wakeword: falling back to Jarvis")
                            }
                            b.build(this)
                        } catch (e: PorcupineException) {
                            Log.e("LifeLogService", "Porcupine init failed", e)
                            return
                        }
                    } else {
                        null
                    }
                porcupine = pp

                val sampleRate = pp?.sampleRate ?: 16_000
                val frameLength = pp?.frameLength ?: 512
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, sampleRate / 2)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord not initialized (state=${rec.state})")
                }

                val frame = ShortArray(frameLength)
                // 0.5s pre-roll ring.
                val preRoll = sampleRate / 2
                val preBuf = ShortArray(preRoll)
                var preIdx = 0
                var preFilled = false

                // Chunked logging (kept small so uploads are manageable).
                val chunkSeconds = 20
                val chunkSamples = sampleRate * chunkSeconds
                val chunkBuf = ShortArray(chunkSamples)
                var chunkIdx = 0

                wakewordRunning = true
                try {
                    rec.startRecording()
                } catch (e: Exception) {
                    throw IllegalStateException("AudioRecord start failed: ${e.message}", e)
                }

                var cooldownUntilMs = 0L
                var consecutiveReadErrors = 0
                while (wakewordRunning && scope.isActive) {
                    val now = System.currentTimeMillis()
                    if (wakewordInFlight || now < cooldownUntilMs) {
                        delay(80)
                        continue
                    }
                    val n = rec.read(frame, 0, frameLength)
                    if (n <= 0) {
                        consecutiveReadErrors += 1
                        if (consecutiveReadErrors >= 40) {
                            throw IllegalStateException("AudioRecord read failing (n=$n), restarting mic loop")
                        }
                        delay(30)
                        continue
                    }
                    consecutiveReadErrors = 0

                    if (logEnabled) {
                        var src = 0
                        while (src < n) {
                            val toCopy = minOf(n - src, chunkSamples - chunkIdx)
                            System.arraycopy(frame, src, chunkBuf, chunkIdx, toCopy)
                            chunkIdx += toCopy
                            src += toCopy
                            if (chunkIdx >= chunkSamples) {
                                enqueueChunkWav(chunkBuf.copyOf(), sampleRate)
                                chunkIdx = 0
                            }
                        }
                    }

                    for (i in 0 until n) {
                        preBuf[preIdx] = frame[i]
                        preIdx += 1
                        if (preIdx >= preBuf.size) {
                            preIdx = 0
                            preFilled = true
                        }
                    }
                    val hit = if (pp != null) {
                        try { pp.process(frame) } catch (_: Exception) { -1 }
                    } else {
                        -1
                    }
                    val isFollowUp = followUpRequested
                    if (isFollowUp) {
                        followUpRequested = false
                        Log.i("LifeLogService", "follow-up listen triggered (no wakeword needed)")
                    }
                    if (hit >= 0 || isFollowUp) {
                        wakewordInFlight = true
                        if (!isFollowUp) playBeep()
                        // capture ~1s pre-roll + up to 3s speech window (early stop on silence)
                        val sr = sampleRate
                        val maxSeconds = 4
                        val minSeconds = 1
                        val maxSamples = sr * maxSeconds
                        val pcm = ShortArray(sr + maxSamples)

                        // copy pre-roll (1s)
                        if (preFilled) {
                            var idx = preIdx
                            for (i in 0 until sr) {
                                pcm[i] = preBuf[idx]
                                idx++
                                if (idx >= preBuf.size) idx = 0
                            }
                        } else {
                            val copyLen = minOf(preIdx, sr)
                            for (i in 0 until copyLen) pcm[i] = preBuf[i]
                        }

                        // temporarily stop the detector recorder
                        try { rec.stop() } catch (_: Exception) { }
                        val qrec = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sr,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            sr * 2
                        )
                        try { qrec.startRecording() } catch (_: Exception) { }
                        var offset = 0
                        val chunk = sr / 10 // 0.1s
                        val silenceThreshold = 650.0
                        val silenceFramesToStop = 9
                        var silenceCount = 0
                        var speechSeen = false
                        while (offset + chunk <= maxSamples) {
                            val r = qrec.read(pcm, sr + offset, chunk)
                            if (r <= 0) continue
                            val rRms = rms(pcm, sr + offset, r)
                            if (rRms > silenceThreshold) {
                                speechSeen = true
                                silenceCount = 0
                            } else if (speechSeen) {
                                silenceCount += 1
                            }
                            offset += r
                            val seconds = offset / sr
                            if (speechSeen && seconds >= minSeconds && silenceCount >= silenceFramesToStop) {
                                break
                            }
                        }
                        try { qrec.stop() } catch (_: Exception) { }
                        try { qrec.release() } catch (_: Exception) { }
                        playEndBeep()

                        val total = sr + offset
                        val wav = toWav(pcm.copyOfRange(0, total), sr)
                        try {
                            val text = api.uploadWavQuery(wav)
                            Log.i("LifeLogService", "wakeword query text=${text ?: "(none)"}")
                        } catch (e: Exception) {
                            Log.w("LifeLogService", "uploadWavQuery failed: ${e.message}")
                        }

                        cooldownUntilMs = System.currentTimeMillis() + 500
                        wakewordInFlight = false
                        try {
                            rec.startRecording()
                        } catch (e: Exception) {
                            Log.w("LifeLogService", "detector resume failed: ${e.message}")
                            delay(250)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LifeLogService", "wakewordLoop restarting: ${e.message}")
            } finally {
                try { rec?.stop() } catch (_: Exception) { }
                try { rec?.release() } catch (_: Exception) { }
                try { porcupine?.delete() } catch (_: Exception) { }
                wakewordRunning = false
                wakewordInFlight = false
            }

            delay(600)
        }
    }

    companion object {
        const val CHANNEL_ID = "lifelog"
        const val NOTIF_ID = 5179
    }
}
