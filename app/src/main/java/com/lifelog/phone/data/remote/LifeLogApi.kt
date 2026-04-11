package com.lifelog.phone.data.remote

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.net.Uri
import android.util.Log
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.local.CalendarEventDao
import com.lifelog.phone.data.local.CalendarEventEntity
import com.lifelog.phone.data.local.FactDao
import com.lifelog.phone.data.local.FactEntity
import com.lifelog.phone.data.local.MessageDao
import com.lifelog.phone.data.local.PhoneLogDao
import com.lifelog.phone.data.local.PhoneLogEntity
import com.lifelog.phone.data.local.RoutineDao
import com.lifelog.phone.data.local.RoutineEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VoiceConversation(
    val id: Int,
    val ts: String,
    val query: String,
    val reply: String,
    val model: String,
    val sessionId: String = "",
    val source: String = ""
)

data class ChatEvidenceItem(
    val ts: String,
    val source: String,
    val text: String
)

data class ChatResult(
    val reply: String,
    val mode: String = "",
    val intent: String = "",
    val timeScope: String = "",
    val windowStart: String = "",
    val windowEnd: String = "",
    val model: String = "",
    val reason: String = "",
    val evidence: List<ChatEvidenceItem> = emptyList(),
    val replyStem: String = ""
)

data class PhoneLogEvent(
    val id: Long,
    val ts: String,
    val deviceId: String,
    val kind: String,
    val text: String,
    val transcriptId: Long = 0L,
    val sourceType: String = "",
    val speakerId: String = "",
    val speakerClusterId: String = "",
    val speakerConfidence: Double = 0.0,
    val tags: String = "",
    val importance: Double = 0.0,
    val mediaLikelihood: Double = 0.0,
    val dialogDensity: Double = 0.0,
    val source: String = ""
)

data class FactItem(
    val id: Long,
    val createdAt: String,
    val category: String,
    val fact: String,
    val source: String
)

data class SuggestionItem(
    val title: String,
    val suggestion: String,
    val reason: String,
    val confidence: Double
)

data class DataQuestionItem(
    val id: String,
    val type: String,
    val title: String,
    val prompt: String,
    val context: String,
    val placeholder: String,
    val options: List<String>,
    val meta: JSONObject
)

data class SpeakerProfileItem(
    val name: String,
    val fileStem: String,
    val hasVector: Boolean
)

data class SpeakerKnownLabelItem(
    val name: String,
    val enrolled: Boolean,
    val pendingSamples: Int
)

data class SpeakerProfilesResult(
    val profiles: List<SpeakerProfileItem>,
    val knownLabels: List<SpeakerKnownLabelItem>,
    val setupRecommended: Boolean,
    val pendingTotal: Int
)

data class TextReplaceResult(
    val totalUpdated: Int,
    val updatedTranscripts: Int,
    val updatedFacts: Int,
    val updatedPhoneEvents: Int,
    val updatedVoiceQueries: Int,
    val updatedVoiceReplies: Int,
    val aliasesAdded: Int
)

data class CalendarSyncEvent(
    val source: String = "local",
    val remoteId: String = "",
    val calendarId: String = "",
    val title: String,
    val startTs: String,
    val endTs: String,
    val timezone: String = "",
    val recurrenceRule: String = "",
    val location: String = "",
    val notes: String = "",
    val isAllDay: Boolean = false,
    val status: String = "confirmed",
    val syncGoogle: Boolean = false
)

data class CalendarEventItem(
    val id: Long,
    val source: String,
    val remoteId: String,
    val calendarId: String,
    val title: String,
    val startTs: String,
    val endTs: String,
    val timezone: String,
    val recurrenceRule: String,
    val location: String,
    val notes: String,
    val isAllDay: Boolean,
    val status: String,
    val updatedAt: String,
    val display: String
)

data class RoutineSyncEvent(
    val title: String,
    val kind: String = "custom",
    val anchorKey: String = "",
    val hourBucket: Int = -1,
    val weekdays: String = "",
    val note: String = "",
    val confidence: Double = 0.0,
    val source: String = "manual",
    val active: Boolean = true,
    val occurrences: Int = 0,
    val firstSeenTs: String = "",
    val lastSeenTs: String = ""
)

data class RoutineItem(
    val id: Long,
    val title: String,
    val kind: String,
    val anchorKey: String,
    val hourBucket: Int,
    val weekdays: String,
    val note: String,
    val confidence: Double,
    val source: String,
    val active: Boolean,
    val occurrences: Int,
    val firstSeenTs: String,
    val lastSeenTs: String,
    val updatedAt: String,
    val createdAt: String,
    val display: String
)

data class Insight(
    val type: String,
    val time: String? = null,
    val text: String,
    val title: String? = null,
    val eventTime: String? = null,
    val location: String? = null
)

data class InsightsResponse(
    val ok: Boolean,
    val insights: Map<String, List<Insight>>
)

@Singleton
class LifeLogApi @Inject constructor(
    val settingsRepository: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val factDao: FactDao,
    private val phoneLogDao: PhoneLogDao,
    private val calendarEventDao: CalendarEventDao,
    private val routineDao: RoutineDao,
    private val whisperEngine: com.lifelog.phone.data.whisper.WhisperEngine,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun authHeader(req: Request.Builder): Request.Builder {
        val token = settingsRepository.cachedLifeLogSyncToken
            .ifBlank { settingsRepository.lifeLogSyncToken }
            .trim()
        if (token.isNotEmpty()) {
            req.header("Authorization", "Bearer $token")
            req.header("X-Phone-Token", token)
        }
        return req
    }

    private fun base(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    suspend fun health(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/health")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use false
                val body = res.body?.string().orEmpty().trim()
                if (body.isEmpty()) return@use true
                return@use try {
                    JSONObject(body).optBoolean("ok", true)
                } catch (_: Exception) {
                    true
                }
            }
        }
    }

    suspend fun chat(baseUrl: String, text: String, sessionId: String = "", voice: Boolean = false): Result<String> =
        chatInternal(baseUrl = baseUrl, text = text, voice = voice, sessionId = sessionId).map { it.reply }

    suspend fun chatWithMeta(
        baseUrl: String,
        text: String,
        sessionId: String = "",
        voice: Boolean = false
    ): Result<ChatResult> = chatInternal(baseUrl = baseUrl, text = text, voice = voice, sessionId = sessionId)

    suspend fun voiceQuery(baseUrl: String, audioWav: ByteArray, sessionId: String = ""): Result<Pair<ChatResult, String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Prefer on-device Whisper; fall back to Termux server if model not downloaded.
            val transcriptText: String
            if (whisperEngine.isReady()) {
                Log.i("LifeLogChat", "transcribing on-device via Whisper")
                val text = whisperEngine.transcribeWav(audioWav)
                if (text.isBlank()) throw IllegalStateException("On-device transcription returned empty result")
                transcriptText = text
            } else {
                Log.i("LifeLogChat", "Whisper model not downloaded, falling back to Termux server")
                val transcribeReq = authHeader(Request.Builder())
                    .url("${base(baseUrl)}/phone/upload_wav_query")
                    .post(audioWav.toRequestBody("audio/wav".toMediaType()))
                    .build()
                val body = client.newCall(transcribeReq).execute().use { res ->
                    val b = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw IllegalStateException("Transcription failed (HTTP ${res.code})")
                    b
                }
                val obj = JSONObject(body)
                val text = obj.optString("text", "").trim()
                if (text.isEmpty()) throw IllegalStateException(obj.optString("error", "transcription_failed"))
                transcriptText = text
            }

            Log.i("LifeLogChat", "transcript=\"$transcriptText\"")
            val chatRes = chatInternal(baseUrl = baseUrl, text = transcriptText, voice = true, sessionId = sessionId)
                .getOrElse { throw it }
            chatRes to transcriptText
        }
    }

    /** True when all Whisper model files are present on device. */
    fun isWhisperDownloaded(): Boolean = whisperEngine.isReady()

    /** Transcribe a WAV byte array on-device. Returns blank string if model not ready or speech empty. */
    suspend fun transcribeOnDevice(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        whisperEngine.transcribeWav(wavBytes)
    }

    fun whisperSizeMb(): Int = WHISPER_SIZE_MB

    /** Download all three Whisper files (encoder, decoder, vocab) with progress. */
    suspend fun downloadWhisper(onProgress: (Int) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            data class ModelFile(val url: String, val name: String, val weight: Int)
            val files = listOf(
                ModelFile(WHISPER_ENCODER_URL, com.lifelog.phone.data.whisper.WhisperEngine.ENCODER_FILE, 44),
                ModelFile(WHISPER_DECODER_URL, com.lifelog.phone.data.whisper.WhisperEngine.DECODER_FILE, 54),
                ModelFile(WHISPER_VOCAB_URL,   com.lifelog.phone.data.whisper.WhisperEngine.VOCAB_FILE,    2),
            )
            var totalWeight = 0
            for (mf in files) {
                val dest = java.io.File(context.filesDir, mf.name)
                if (dest.exists() && dest.length() > 1024L) {
                    totalWeight += mf.weight; onProgress(totalWeight); continue
                }
                val tmp = java.io.File(context.filesDir, "${mf.name}.tmp")
                val req = Request.Builder().url(mf.url).build()
                client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw IllegalStateException("Download failed for ${mf.name}: HTTP ${res.code}")
                        val body = res.body ?: throw IllegalStateException("Empty response for ${mf.name}")
                        val total = body.contentLength().takeIf { it > 0 } ?: (mf.weight * 1_000_000L)
                        var downloaded = 0L
                        tmp.outputStream().use { out ->
                            body.byteStream().use { src ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (src.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    val filePct = (mf.weight * downloaded / total).toInt().coerceIn(0, mf.weight)
                                    onProgress(totalWeight + filePct)
                                }
                            }
                        }
                        tmp.renameTo(dest)
                    }
                totalWeight += mf.weight
                onProgress(totalWeight)
            }
        }
    }

    suspend fun getRecentConversations(
        baseUrl: String,
        sinceId: Int = 0,
        limit: Int = 50
    ): Result<List<VoiceConversation>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 50)
            val safeSince = sinceId.coerceAtLeast(0)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/conversations/recent?since_id=$safeSince&limit=$safeLimit")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Conversation sync failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr: JSONArray = root.optJSONArray("conversations") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            VoiceConversation(
                                id = obj.optInt("id", 0),
                                ts = obj.optString("ts", ""),
                                query = obj.optString("query", ""),
                                reply = obj.optString("reply", ""),
                                model = obj.optString("model", ""),
                                sessionId = obj.optString("session_id", ""),
                                source = obj.optString("source", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getPhoneLogs(
        baseUrl: String,
        limit: Int = 120,
        kind: String = ""
    ): Result<List<PhoneLogEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 500)
            val kindParam = kind.trim()
            val suffix = if (kindParam.isNotEmpty()) {
                "?limit=$safeLimit&kind=$kindParam"
            } else {
                "?limit=$safeLimit"
            }

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/logs$suffix")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Logs fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("events") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            PhoneLogEvent(
                                id = obj.optLong("id", 0L),
                                ts = obj.optString("ts", ""),
                                deviceId = obj.optString("device_id", ""),
                                kind = obj.optString("kind", ""),
                                text = obj.optString("text", ""),
                                transcriptId = obj.optLong("transcript_id", 0L),
                                sourceType = obj.optString("source_type", ""),
                                speakerId = obj.optString("speaker_id", ""),
                                speakerClusterId = obj.optString("speaker_cluster_id", ""),
                                speakerConfidence = obj.optDouble("speaker_confidence", 0.0),
                                tags = obj.optString("tags", ""),
                                importance = obj.optDouble("importance", 0.0),
                                mediaLikelihood = obj.optDouble("media_likelihood", 0.0),
                                dialogDensity = obj.optDouble("dialog_density", 0.0),
                                source = obj.optString("source", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getFacts(
        baseUrl: String,
        limit: Int = 160,
        query: String = ""
    ): Result<List<FactItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 500)
            val q = query.trim()
            val suffix = if (q.isNotEmpty()) {
                "?limit=$safeLimit&q=${Uri.encode(q)}"
            } else {
                "?limit=$safeLimit"
            }

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/facts$suffix")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Facts fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("facts") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            FactItem(
                                id = obj.optLong("id", 0L),
                                createdAt = obj.optString("created_at", ""),
                                category = obj.optString("category", ""),
                                fact = obj.optString("fact", ""),
                                source = obj.optString("source", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getSuggestions(
        baseUrl: String,
        limit: Int = 4,
        days: Int = 7
    ): Result<List<SuggestionItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 8)
            val safeDays = days.coerceIn(1, 30)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/suggestions?limit=$safeLimit&days=$safeDays")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Suggestions fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("suggestions") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            SuggestionItem(
                                title = obj.optString("title", ""),
                                suggestion = obj.optString("suggestion", ""),
                                reason = obj.optString("reason", ""),
                                confidence = obj.optDouble("confidence", 0.0)
                            )
                        )
                    }
                }
            }
            }
        }

    suspend fun getQuestions(
        baseUrl: String,
        limit: Int = 12,
        days: Int = 7
    ): Result<List<DataQuestionItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 24)
            val safeDays = days.coerceIn(1, 30)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/questions?limit=$safeLimit&days=$safeDays")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Questions fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("questions") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val optionsJson = obj.optJSONArray("options") ?: JSONArray()
                        val options = buildList {
                            for (j in 0 until optionsJson.length()) {
                                val v = optionsJson.optString(j, "").trim()
                                if (v.isNotEmpty()) add(v)
                            }
                        }
                        add(
                            DataQuestionItem(
                                id = obj.optString("id", ""),
                                type = obj.optString("type", ""),
                                title = obj.optString("title", ""),
                                prompt = obj.optString("prompt", ""),
                                context = obj.optString("context", ""),
                                placeholder = obj.optString("placeholder", ""),
                                options = options,
                                meta = obj.optJSONObject("meta") ?: JSONObject()
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getSpeakerProfiles(
        baseUrl: String
    ): Result<SpeakerProfilesResult> = withContext(Dispatchers.IO) {
        runCatching {
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/speakers/profiles")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Speaker profiles fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
                val knownArr = root.optJSONArray("known_labels") ?: JSONArray()
                val profiles = buildList {
                    for (i in 0 until profilesArr.length()) {
                        val obj = profilesArr.optJSONObject(i) ?: continue
                        add(
                            SpeakerProfileItem(
                                name = obj.optString("name", ""),
                                fileStem = obj.optString("file_stem", ""),
                                hasVector = obj.optBoolean("has_vector", false)
                            )
                        )
                    }
                }
                val known = buildList {
                    for (i in 0 until knownArr.length()) {
                        val obj = knownArr.optJSONObject(i) ?: continue
                        add(
                            SpeakerKnownLabelItem(
                                name = obj.optString("name", ""),
                                enrolled = obj.optBoolean("enrolled", false),
                                pendingSamples = obj.optInt("pending_samples", 0)
                            )
                        )
                    }
                }
                SpeakerProfilesResult(
                    profiles = profiles,
                    knownLabels = known,
                    setupRecommended = root.optBoolean("setup_recommended", profiles.isEmpty()),
                    pendingTotal = root.optInt("pending_total", 0)
                )
            }
        }
    }

    suspend fun enrollSpeaker(
        baseUrl: String,
        speaker: String,
        audioWav: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSpeaker = speaker.trim()
            if (cleanSpeaker.isEmpty()) {
                throw IllegalStateException("missing speaker")
            }
            if (audioWav.isEmpty()) {
                throw IllegalStateException("missing audio")
            }
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/speakers/enroll?speaker=${Uri.encode(cleanSpeaker)}")
                .post(audioWav.toRequestBody("audio/wav".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = try {
                        JSONObject(body).optString("error", "")
                    } catch (_: Exception) {
                        ""
                    }
                    throw IllegalStateException(
                        if (msg.isNotBlank()) msg else "Speaker enroll failed (HTTP ${res.code})"
                    )
                }
                val root = JSONObject(body)
                root.optString("message", "enrolled")
            }
        }
    }

    suspend fun getCalendarEvents(
        baseUrl: String,
        limit: Int = 160,
        startTs: String = "",
        endTs: String = "",
        source: String = "",
        syncGoogle: Boolean = false
    ): Result<List<CalendarEventItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 500)
            val args = mutableListOf("limit=$safeLimit")
            val start = startTs.trim()
            val end = endTs.trim()
            val src = source.trim().lowercase()
            if (start.isNotEmpty()) args.add("start_ts=${Uri.encode(start)}")
            if (end.isNotEmpty()) args.add("end_ts=${Uri.encode(end)}")
            if (src == "local" || src == "google") args.add("source=$src")
            if (syncGoogle) args.add("sync_google=1")
            val query = args.joinToString("&")

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/calendar/events?$query")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Calendar fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("events") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            CalendarEventItem(
                                id = obj.optLong("id", 0L),
                                source = obj.optString("source", "local"),
                                remoteId = obj.optString("remote_id", ""),
                                calendarId = obj.optString("calendar_id", ""),
                                title = obj.optString("title", ""),
                                startTs = obj.optString("start_ts", ""),
                                endTs = obj.optString("end_ts", ""),
                                timezone = obj.optString("timezone", ""),
                                recurrenceRule = obj.optString("recurrence_rule", ""),
                                location = obj.optString("location", ""),
                                notes = obj.optString("notes", ""),
                                isAllDay = obj.optBoolean("is_all_day", false),
                                status = obj.optString("status", "confirmed"),
                                updatedAt = obj.optString("updated_at", ""),
                                display = obj.optString("display", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getRoutines(
        baseUrl: String,
        limit: Int = 160,
        query: String = "",
        active: String = "active",
        detect: Boolean = true,
        detectDays: Int = 14
    ): Result<List<RoutineItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 500)
            val args = mutableListOf("limit=$safeLimit")
            val q = query.trim()
            if (q.isNotEmpty()) args.add("q=${Uri.encode(q)}")
            val activeFilter = active.trim().lowercase()
            if (activeFilter in setOf("all", "active", "inactive", "1", "0")) {
                args.add("active=${Uri.encode(activeFilter)}")
            }
            if (detect) args.add("detect=1") else args.add("detect=0")
            args.add("detect_days=${detectDays.coerceIn(3, 60)}")
            val queryString = args.joinToString("&")

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/routines?$queryString")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Routines fetch failed (HTTP ${res.code})")
                }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("routines") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            RoutineItem(
                                id = obj.optLong("id", 0L),
                                title = obj.optString("title", ""),
                                kind = obj.optString("kind", ""),
                                anchorKey = obj.optString("anchor_key", ""),
                                hourBucket = obj.optInt("hour_bucket", -1),
                                weekdays = obj.optString("weekdays", ""),
                                note = obj.optString("note", ""),
                                confidence = obj.optDouble("confidence", 0.0),
                                source = obj.optString("source", ""),
                                active = obj.optBoolean("active", true),
                                occurrences = obj.optInt("occurrences", 0),
                                firstSeenTs = obj.optString("first_seen_ts", ""),
                                lastSeenTs = obj.optString("last_seen_ts", ""),
                                updatedAt = obj.optString("updated_at", ""),
                                createdAt = obj.optString("created_at", ""),
                                display = obj.optString("display", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun upsertRoutine(
        baseUrl: String,
        routine: RoutineSyncEvent,
        id: Long? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("title", routine.title.trim())
                .put("kind", routine.kind.trim())
                .put("anchor_key", routine.anchorKey.trim())
                .put("hour_bucket", routine.hourBucket)
                .put("weekdays", routine.weekdays.trim())
                .put("note", routine.note.trim())
                .put("confidence", routine.confidence)
                .put("source", routine.source.trim())
                .put("active", routine.active)
                .put("occurrences", routine.occurrences)
                .put("first_seen_ts", routine.firstSeenTs.trim())
                .put("last_seen_ts", routine.lastSeenTs.trim())
            if (id != null && id > 0L) payload.put("id", id)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/routines/upsert")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Routine save failed (HTTP ${res.code})")
                }
                JSONObject(body).optLong("id", 0L)
            }
        }
    }

    suspend fun deleteRoutine(baseUrl: String, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("id", id)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/routines/delete")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Routine delete failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun upsertFact(
        baseUrl: String,
        id: Long?,
        fact: String,
        category: String = "general"
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("fact", fact.trim())
                .put("category", category.trim())
            if (id != null && id > 0L) payload.put("id", id)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/facts/upsert")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Fact save failed (HTTP ${res.code})")
                }
                JSONObject(body).optLong("id", 0L)
            }
        }
    }

    suspend fun deleteFact(baseUrl: String, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("id", id)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/facts/delete")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Fact delete failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun upsertLog(
        baseUrl: String,
        id: Long?,
        kind: String,
        text: String,
        ts: String = "",
        deviceId: String = ""
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("kind", kind.trim())
                .put("text", text.trim())
            if (id != null && id > 0L) payload.put("id", id)
            if (ts.isNotBlank()) payload.put("ts", ts.trim())
            if (deviceId.isNotBlank()) payload.put("device_id", deviceId.trim())

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/logs/upsert")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Log save failed (HTTP ${res.code})")
                }
                JSONObject(body).optLong("id", 0L)
            }
        }
    }

    suspend fun deleteLog(baseUrl: String, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("id", id)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/logs/delete")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Log delete failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun upsertTranscript(
        baseUrl: String,
        id: Long,
        text: String? = null,
        sourceType: String? = null,
        speakerId: String? = null,
        speakerConfidence: Double? = null,
        tags: String? = null,
        importance: Double? = null,
        mediaLikelihood: Double? = null,
        dialogDensity: Double? = null,
        refreshEmbedding: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("id", id).put("refresh_embedding", refreshEmbedding)
            if (text != null) payload.put("text", text)
            if (sourceType != null) payload.put("source_type", sourceType)
            if (speakerId != null) payload.put("speaker_id", speakerId)
            if (speakerConfidence != null) payload.put("speaker_confidence", speakerConfidence)
            if (tags != null) payload.put("tags", tags)
            if (importance != null) payload.put("importance", importance)
            if (mediaLikelihood != null) payload.put("media_likelihood", mediaLikelihood)
            if (dialogDensity != null) payload.put("dialog_density", dialogDensity)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/transcripts/upsert")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Transcript save failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun answerQuestion(
        baseUrl: String,
        item: DataQuestionItem,
        answer: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("id", item.id)
                .put("type", item.type)
                .put("answer", answer.trim())
                .put("meta", item.meta)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/questions/answer")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Question answer failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun submitMealResponse(
        baseUrl: String,
        mealKey: String,
        answer: String,
        day: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val meal = mealKey.trim().lowercase()
            val response = answer.trim()
            val payload = JSONObject()
                .put("id", "meal_chat_${System.currentTimeMillis()}")
                .put("type", "meal_check")
                .put("answer", response)
                .put("day", day.trim())
                .put(
                    "meta",
                    JSONObject()
                        .put("missing", JSONArray().put(meal))
                        .put("source", "chat_meal_reply")
                )
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/questions/answer")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Meal response save failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun replaceTextEverywhere(
        baseUrl: String,
        wrong: String,
        correct: String,
        addAlias: Boolean = true
    ): Result<TextReplaceResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("wrong", wrong.trim())
                .put("correct", correct.trim())
                .put("add_alias", addAlias)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/text/replace")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Text replace failed (HTTP ${res.code})")
                }
                val root = JSONObject(body)
                val updated = root.optJSONObject("updated") ?: JSONObject()
                TextReplaceResult(
                    totalUpdated = root.optInt("total_updated", 0),
                    updatedTranscripts = updated.optInt("transcripts", 0),
                    updatedFacts = updated.optInt("facts", 0),
                    updatedPhoneEvents = updated.optInt("phone_events", 0),
                    updatedVoiceQueries = updated.optInt("voice_queries", 0),
                    updatedVoiceReplies = updated.optInt("voice_replies", 0),
                    aliasesAdded = updated.optInt("aliases_added", 0)
                )
            }
        }
    }

    suspend fun upsertCalendarEvent(
        baseUrl: String,
        event: CalendarSyncEvent,
        id: Long? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("source", event.source.trim())
                .put("remote_id", event.remoteId.trim())
                .put("calendar_id", event.calendarId.trim())
                .put("title", event.title.trim())
                .put("start_ts", event.startTs.trim())
                .put("end_ts", event.endTs.trim())
                .put("timezone", event.timezone.trim())
                .put("recurrence_rule", event.recurrenceRule.trim())
                .put("location", event.location.trim())
                .put("notes", event.notes.trim())
                .put("is_all_day", event.isAllDay)
                .put("status", event.status.trim())
                .put("sync_google", event.syncGoogle)
            if (id != null && id > 0L) payload.put("id", id)

            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/calendar/upsert")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Calendar upsert failed (HTTP ${res.code})")
                }
                JSONObject(body).optLong("id", 0L)
            }
        }
    }

    suspend fun deleteCalendarEvent(
        baseUrl: String,
        id: Long,
        syncGoogle: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("id", id)
                .put("sync_google", syncGoogle)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/calendar/delete")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Calendar delete failed (HTTP ${res.code})")
                }
            }
        }
    }

    suspend fun updateSetting(baseUrl: String, key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("key", key)
                .put("value", value)
            val req = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/setting")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Setting update failed (HTTP ${res.code})")
                }
            }
        }
    }

    private suspend fun chatInternal(baseUrl: String, text: String, voice: Boolean, sessionId: String = ""): Result<ChatResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val syncBaseUrl = settingsRepository.cachedLifeLogSyncUrl
                    .ifBlank { settingsRepository.lifeLogSyncUrl }
                    .ifBlank { baseUrl }
                    .trim()
                // Sync in background — never block the chat response waiting on the server
                if (syncBaseUrl.isNotBlank()) {
                    kotlinx.coroutines.GlobalScope.launch {
                        runCatching { syncLocalMirror(syncBaseUrl) }
                    }
                }
                buildDirectLocalAnswer(text)?.let { return@runCatching it }
                val provider = settingsRepository.cachedAiProvider.trim().lowercase()
                Log.i("LifeLogChat", "query=\"$text\" provider=$provider")
                when (provider) {
                    "on-device" -> {
                        // Try Gemini Nano first on supported devices, then fall back to MediaPipe.
                        try { chatGeminiNano(text) } catch (_: Exception) { chatOnDevice(text) }
                    }
                    "ollama" -> chatOllama(text)
                    "gemini" -> chatGemini(text)
                    else -> {
                        // Default: use the downloaded on-device model when present, else cloud Gemini.
                        if (isOnDeviceModelDownloaded()) chatOnDevice(text) else chatGemini(text)
                    }
                }
            }
        }

    private fun chatOllama(text: String): ChatResult {
        val url = settingsRepository.ollamaUrl.trim().trimEnd('/')
        val model = settingsRepository.ollamaModel.trim().ifBlank { "gemma4:4b" }
        val systemPrompt = settingsRepository.cachedAssistantSystemPrompt.trim()

        val messages = JSONArray()
        if (systemPrompt.isNotEmpty()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.put(JSONObject().put("role", "user").put("content", text))

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)

        val ollamaClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val req = Request.Builder()
            .url("$url/api/chat")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        ollamaClient.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Ollama error (HTTP ${res.code})")
            val obj = JSONObject(body)
            val reply = obj.getJSONObject("message").getString("content").trim()
            if (reply.isEmpty()) throw IllegalStateException("empty_reply")
            return ChatResult(reply = reply, mode = "llm", model = model)
        }
    }

    private fun chatGemini(text: String): ChatResult {
        val apiKey = settingsRepository.geminiApiKey.trim()
        if (apiKey.isEmpty()) throw IllegalStateException("Gemini API key not set — go to Settings")
        val model = settingsRepository.geminiModel.trim().ifBlank { "gemini-2.0-flash" }
        val systemPrompt = settingsRepository.cachedAssistantSystemPrompt.trim()

        val contents = JSONArray()
        contents.put(JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))
        val payload = JSONObject().put("contents", contents)
        if (systemPrompt.isNotEmpty()) {
            payload.put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Gemini error (HTTP ${res.code}): $body")
            val obj = JSONObject(body)
            val reply = obj.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text").trim()
            if (reply.isEmpty()) throw IllegalStateException("empty_reply")
            return ChatResult(reply = reply, mode = "llm", model = model)
        }
    }

    // ── Gemini Nano (on-device, no internet required) ────────────────────

    private suspend fun chatGeminiNano(text: String): ChatResult {
        if (Build.VERSION.SDK_INT < 31) throw IllegalStateException("Gemini Nano requires Android 12+")
        return withContext(Dispatchers.Main) {
            val systemPrompt = settingsRepository.cachedAssistantSystemPrompt.trim()
            val memory = withContext(Dispatchers.IO) { buildLocalMemoryContext(text) }
            val prompt = buildString {
                if (systemPrompt.isNotEmpty()) append(systemPrompt)
                if (memory.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(memory)
                }
                if (isNotEmpty()) append("\n\n")
                append(text)
            }
            val config = generationConfig { temperature = 0.2f; topK = 16 }
            val model = GenerativeModel(generationConfig = config)
            val response = model.generateContent(prompt)
            val reply = response.text?.trim().orEmpty()
            if (reply.isEmpty()) throw IllegalStateException("Gemini Nano returned empty reply")
            ChatResult(reply = reply, mode = "llm", model = "gemini-nano")
        }
    }

    suspend fun checkGeminiNanoAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return withContext(Dispatchers.Main) {
            try {
                // Try a minimal inference call to verify availability
                val model = GenerativeModel(generationConfig = generationConfig { temperature = 0f })
                // Use reflection to call checkAvailability if it exists, otherwise probe with a test call
                val method = model.javaClass.methods.firstOrNull {
                    it.name.contains("availability", ignoreCase = true) ||
                    it.name.contains("check", ignoreCase = true)
                }
                if (method != null) {
                    val result = method.invoke(model)
                    result?.toString()?.let { it.contains("AVAILABLE") } ?: false
                } else {
                    // Fallback: try a trivial generation to see if Nano responds
                    val response = model.generateContent("hi")
                    response.text?.isNotEmpty() == true
                }
            } catch (_: Exception) { false }
        }
    }

    // ── LiteRT-LM on-device inference fallback (Gemma 4) ───────────────────

    companion object {
        // Official Gemma 4 Android fallback path for broad device support is LiteRT-LM.
        const val ON_DEVICE_MODEL_FILE = "gemma-4-E4B-it.litertlm"
        const val ON_DEVICE_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        const val ON_DEVICE_MODEL_SIZE_MB = 3740

        // Speaker embedding model (ONNX). Can be replaced in Settings.
        const val SPEAKER_MODEL_FILE = "speaker_ecapa.onnx"
        const val SPEAKER_MODEL_URL =
            "https://huggingface.co/Xenova/wavlm-base-plus-sv/resolve/main/onnx/model.onnx?download=true"
        const val SPEAKER_MODEL_SIZE_MB = 95

        // On-device Whisper ASR (ONNX). English-only base model.
        const val WHISPER_ENCODER_URL =
            "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/encoder_model.onnx"
        const val WHISPER_DECODER_URL =
            "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/decoder_model.onnx"
        const val WHISPER_VOCAB_URL =
            "https://huggingface.co/openai/whisper-base/resolve/main/vocab.json"
        const val WHISPER_SIZE_MB = 150  // encoder ~55 MB + decoder ~94 MB + vocab ~1 MB
    }

    @Volatile private var liteRtEngine: com.google.ai.edge.litertlm.Engine? = null
    private val liteRtMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun getOrCreateEngine(): com.google.ai.edge.litertlm.Engine =
        withContext(Dispatchers.IO) {
            liteRtEngine?.let { return@withContext it }
            val modelPath = context.filesDir.absolutePath + "/$ON_DEVICE_MODEL_FILE"
            Log.i("LifeLogChat", "creating engine from modelPath=$modelPath exists=${java.io.File(modelPath).exists()} size=${java.io.File(modelPath).length()}")
            val config = com.google.ai.edge.litertlm.EngineConfig(
                modelPath = modelPath,
                cacheDir = context.cacheDir.absolutePath
            )
            Log.i("LifeLogChat", "Engine config created, calling initialize...")
            com.google.ai.edge.litertlm.Engine(config).also { engine ->
                engine.initialize()
                liteRtEngine = engine
                Log.i("LifeLogChat", "Engine initialized successfully")
            }
        }

    private suspend fun buildDirectLocalAnswer(text: String): ChatResult? {
        val query = text.trim().lowercase()
        if (query.isBlank()) return null

        if (query.length < 5 || query in setOf("how", "hi", "hello", "hey")) {
            return ChatResult(
                reply = "Hey - I can help with things like 'what happened today', 'where have I been today', or 'give me a quick recap of my week'.",
                mode = "local",
                model = "local-memory",
                reason = "Prompt too short for reliable intent detection.",
            )
        }

        val recentLogs = phoneLogDao.getRecent(24)
        val recentCalendar = calendarEventDao.getAll(12)

        if ((query.contains("where") && query.contains("today")) || query.contains("where have i been")) {
            val allLocationLogs = phoneLogDao.getRecent(240)
                .filter { it.kind.equals("location", ignoreCase = true) }
            val nowMs = System.currentTimeMillis()
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val todayLogs = allLocationLogs.filter {
                val ts = parseTsMillis(it.ts)
                if (ts <= 0L) return@filter false
                java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today
            }
            val earlierMode = query.contains("earlier") || query.contains("before")
            val earlierCutoff = nowMs - (2L * 60L * 60L * 1000L)
            val locationLogs = if (earlierMode) {
                todayLogs.filter { parseTsMillis(it.ts) in 1L until earlierCutoff }
            } else {
                todayLogs
            }
            if (locationLogs.isEmpty()) {
                return ChatResult(
                    reply = if (earlierMode) {
                        "I don't see earlier-today location records on this phone yet."
                    } else {
                        "I don't see location records on this phone yet for today."
                    },
                    mode = "local",
                    model = "local-memory",
                    reason = "No local location rows found.",
                )
            }
            val lines = locationLogs.take(6).map {
                ChatEvidenceItem(ts = it.ts, source = it.kind.ifBlank { "location" }, text = it.text)
            }
            val labelCache = mutableMapOf<String, String>()
            val summary = locationLogs.take(4).joinToString("\n") { row ->
                "- ${formatEvidenceTimestamp(row.ts)}: ${humanizeLocationText(row.text, labelCache)}"
            }
            return ChatResult(
                reply = "Here is your recent location history from this phone:\n$summary",
                mode = "local",
                intent = "location_history",
                timeScope = if (earlierMode) "earlier_today" else "today",
                model = "local-memory",
                reason = "Answered directly from local phone location logs.",
                evidence = lines
            )
        }

        if (query.contains("reminder") || query.contains("notification") || query.contains("prompt")) {
            val reminderLogs = recentLogs.filter {
                val kind = it.kind.lowercase()
                kind == "reminder" || kind == "meal_response" || kind == "location_confirmed" || kind == "location_corrected"
            }
            if (reminderLogs.isEmpty()) {
                return ChatResult(
                    reply = "I don't see reminder-related records on this phone yet.",
                    mode = "local",
                    model = "local-memory",
                    reason = "No local reminder rows found.",
                )
            }
            val lines = reminderLogs.take(6).map {
                ChatEvidenceItem(ts = it.ts, source = it.kind.ifBlank { "reminder" }, text = it.text)
            }
            val summary = reminderLogs.take(5).joinToString("\n") { row ->
                "- ${formatEvidenceTimestamp(row.ts)}: ${humanizeLogText(row.kind, row.text)}"
            }
            return ChatResult(
                reply = "Here is what I found in your recent reminder history:\n$summary",
                mode = "local",
                intent = "summary",
                timeScope = "recent",
                model = "local-memory",
                reason = "Answered directly from local reminder-related logs.",
                evidence = lines
            )
        }

        if (query.contains("calendar") || query.contains("schedule") || query.contains("event")) {
            if (recentCalendar.isEmpty()) {
                return ChatResult(
                    reply = "I don't see any local calendar events on this phone yet.",
                    mode = "local",
                    model = "local-memory",
                    reason = "No local calendar rows found.",
                )
            }
            val lines = recentCalendar.take(6).map {
                val textLine = listOf(it.title, it.location, it.notes).filter { value -> value.isNotBlank() }.joinToString(" | ")
                ChatEvidenceItem(ts = it.startTs, source = "calendar", text = textLine)
            }
            val summary = recentCalendar.take(5).joinToString("\n") { row ->
                val textLine = listOf(row.title, row.location, row.notes).filter { value -> value.isNotBlank() }.joinToString(" | ")
                "- ${formatEvidenceTimestamp(row.startTs)}: $textLine"
            }
            return ChatResult(
                reply = "From your phone's local calendar, I found:\n$summary",
                mode = "local",
                intent = "plans",
                timeScope = "recent",
                model = "local-memory",
                reason = "Answered directly from local calendar rows.",
                evidence = lines
            )
        }

        if (
            query.contains("personal life") ||
            query.contains("personal info") ||
            query.contains("know about me") ||
            query.contains("know about my life") ||
            query.contains("access to my life") ||
            query.contains("access to my personal") ||
            query.contains("what do you know about me")
        ) {
            return buildDirectPersonalMemoryAnswer()
        }

        val recapScope = detectRecapScope(query)
        if (recapScope != null) {
            val fallback = when (recapScope) {
                "tonight", "night", "evening" -> buildDirectRecapAnswer(days = 1, label = "tonight")
                "this week", "week" -> buildDirectRecapAnswer(days = 7, label = "this week")
                "yesterday" -> buildDirectRecapAnswer(days = 2, label = "yesterday")
                else -> buildDirectRecapAnswer(days = 1, label = "today")
            }
            return rewriteRecapWithOnDeviceModel(recapScope, fallback)
        }

        return null
    }

    private suspend fun buildDirectRecapAnswer(days: Int, label: String): ChatResult {
        val now = System.currentTimeMillis()
        val cutoff = now - (days.toLong() * 24L * 60L * 60L * 1000L)
        val calendarUpperBound = if (label == "today" || label == "tonight") now + (2L * 60L * 60L * 1000L) else Long.MAX_VALUE
        val recentLogs = phoneLogDao.getRecent(96)
            .filter { parseTsMillis(it.ts) >= cutoff }
            .sortedByDescending { parseTsMillis(it.ts) }
        val recentMessages = messageDao.getRecent(24)
            .filter { it.timestamp >= cutoff && !it.text.startsWith("---- New conversation") }
            .sortedByDescending { it.timestamp }
        val recentCalendar = calendarEventDao.getAll(24)
            .filter {
                val ts = parseTsMillis(it.startTs)
                ts >= cutoff && ts <= calendarUpperBound
            }
            .sortedBy { parseTsMillis(it.startTs) }

        val evidence = mutableListOf<ChatEvidenceItem>()
        val bullets = mutableListOf<String>()
        var conversationCount = 0
        var locationCount = 0
        var reminderCount = 0
        var mealCount = 0
        val activityMentions = linkedSetOf<String>()

        val recapLogs = recentLogs
            .filter { it.kind.lowercase() in setOf("location", "reminder", "meal_response", "location_confirmed", "location_corrected", "transcript", "note") }
            .filter {
                if (!it.kind.equals("transcript", ignoreCase = true)) return@filter true
                val clean = it.text.trim()
                clean.length >= 10 && clean.split(Regex("\\s+")).size >= 3 && !isLowQualityTranscriptForRecap(clean)
            }
            .distinctBy {
                val keyText = it.text.lowercase().replace(Regex("\\s+"), " ").trim().take(90)
                "${it.kind.lowercase()}|$keyText"
            }
            .take(18)
            .sortedBy { parseTsMillis(it.ts) }

        recapLogs.forEach { row ->
            val kind = row.kind.lowercase()
            val label = when (kind) {
                "transcript" -> {
                    conversationCount += 1
                    val speaker = row.speakerId.trim().ifBlank { "Unknown" }
                    "Conversation ($speaker)"
                }
                "meal_response" -> {
                    mealCount += 1
                    activityMentions += cleanPrimaryUserMention(row.text)
                    "Meal"
                }
                "location_confirmed" -> {
                    locationCount += 1
                    "Location"
                }
                "location_corrected" -> {
                    locationCount += 1
                    "Location"
                }
                "location" -> {
                    locationCount += 1
                    "Location"
                }
                "reminder" -> {
                    reminderCount += 1
                    "Reminder"
                }
                else -> row.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            val cleanText = recapEvidenceText(row.kind, row.text)
            bullets += "- $label: ${formatEvidenceTimestamp(row.ts)}: $cleanText"
            evidence += ChatEvidenceItem(row.ts, row.kind, cleanText)
        }

        if (bullets.size < 4) {
            recentCalendar.take(2).forEach { event ->
                val summary = listOf(event.title, event.location, event.notes)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                    .replace(Regex("https?://\\S+"), "")
                    .replace("Manage my ECAL", "", ignoreCase = true)
                    .replace("Buy Tickets", "", ignoreCase = true)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (summary.isNotBlank()) {
                    bullets += "- Calendar: ${formatEvidenceTimestamp(event.startTs)}: $summary"
                    evidence += ChatEvidenceItem(event.startTs, "calendar", summary)
                }
            }
        }

        if (bullets.size < 4) {
            val recapMessages = recentMessages
                .filter { it.role == "user" || it.role == "assistant" }
                .filter { it.text.trim().length >= 12 }
                .filterNot {
                    val t = it.text.lowercase().trim()
                    t == "how" ||
                        t == "hello" ||
                        t.startsWith("error:") ||
                        t.startsWith("---- new conversation") ||
                        t.startsWith("quick reminder:") ||
                        isRecapPromptLike(t) ||
                        isAssistantSummaryLike(t)
                }
                .filterNot { isLowQualityTranscriptForRecap(it.text) }

            val preferredUserMessages = recapMessages
                .filter { it.role == "user" }
                .filter { isUserActivityMessage(it.text) }
                .take(2)

            preferredUserMessages.forEach { msg ->
                val clean = cleanPrimaryUserMention(msg.text)
                if (clean.isNotBlank()) activityMentions += clean
            }

            preferredUserMessages
                .distinctBy { it.id }
                .forEach { row ->
                    val prefix = "You"
                    val clipped = row.text.take(140)
                    bullets += "- $prefix: ${formatEvidenceTimestampFromMillis(row.timestamp)}: $clipped"
                    evidence += ChatEvidenceItem(formatEvidenceTimestampFromMillis(row.timestamp), row.role, clipped)
                }
        }

        if (bullets.isEmpty()) {
            return ChatResult(
                reply = "I don't have enough local LifeLog data on this phone yet to recap $label clearly.",
                mode = "local",
                model = "local-memory",
                reason = "No recent local logs, calendar events, or chat history found."
            )
        }

        val locationOnly = evidence.isNotEmpty() && evidence.all {
            val src = it.source.lowercase()
            src == "location" || src == "location_confirmed" || src == "location_corrected"
        }

        if (locationOnly) {
            val locationLines = bullets.take(3).joinToString("\n")
            val period = when (label) {
                "tonight" -> "tonight"
                "today" -> "today"
                "yesterday" -> "yesterday"
                "this week" -> "this week"
                else -> label
            }
            return ChatResult(
                reply = buildString {
                    append("I don't have much rich context for $period yet, but it looks like you were mostly moving between places rather than logging conversations or notes.")
                    append('\n')
                    append("Here is the strongest signal I do have:")
                    append('\n')
                    append(locationLines)
                    append('\n')
                    append("If you want better day recaps, keep the app open a bit more and use chat or live transcript occasionally so I have more than location history to work with.")
                },
                mode = "local",
                intent = "summary",
                timeScope = label,
                model = "local-memory",
                reason = "Only location evidence was available for this period.",
                evidence = evidence.take(3)
            )
        }

        val intro = when (label) {
            "today" -> buildString {
                append(buildRecapTakeaway(label, conversationCount, locationCount, reminderCount, mealCount, recentCalendar.size, activityMentions.toList()))
                append("\nHere are the clearest highlights:")
            }
            "tonight" -> buildString {
                append(buildRecapTakeaway(label, conversationCount, locationCount, reminderCount, mealCount, recentCalendar.size, activityMentions.toList()))
                append("\nHere are the clearest highlights:")
            }
            "yesterday" -> buildString {
                append(buildRecapTakeaway(label, conversationCount, locationCount, reminderCount, mealCount, recentCalendar.size, activityMentions.toList()))
                append("\nHere are the clearest highlights:")
            }
            "this week" -> buildString {
                append(buildRecapTakeaway(label, conversationCount, locationCount, reminderCount, mealCount, recentCalendar.size, activityMentions.toList()))
                append("\nHere are the clearest highlights:")
            }
            else -> buildString {
                append(buildRecapTakeaway(label, conversationCount, locationCount, reminderCount, mealCount, recentCalendar.size, activityMentions.toList()))
                append("\nHere are the clearest highlights:")
            }
        }

        val normalizedActivityMentions = activityMentions
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .distinct()

        val highlightLines = bullets
            .distinct()
            .filterNot { line ->
                val lower = line.lowercase()
                normalizedActivityMentions.any { mention ->
                    mention.isNotBlank() && lower.contains(mention.lowercase()) && lower.startsWith("- meal:")
                } || (
                    normalizedActivityMentions.isNotEmpty() &&
                        lower.startsWith("- you:")
                )
            }
            .take(3)

        val finalHighlights = buildList {
            normalizedActivityMentions.firstOrNull()?.let { add("- Logged: $it") }
            addAll(highlightLines)
        }.distinct().take(3)

        return ChatResult(
            reply = buildString {
                append(intro)
                append('\n')
                finalHighlights.forEach { line ->
                    append(line)
                    append('\n')
                }
            }.trim(),
            mode = "local",
            intent = "summary",
            timeScope = label,
            model = "local-memory",
            reason = "Answered directly from local logs, calendar, and chat history.",
            evidence = evidence.take(10)
        )
    }

    private suspend fun rewriteRecapWithOnDeviceModel(label: String, fallback: ChatResult): ChatResult {
        if (!isOnDeviceModelDownloaded()) return fallback
        if (fallback.evidence.isEmpty()) return fallback
        val instruction = settingsRepository.cachedAssistantSystemPrompt.trim()
        val evidenceBlock = fallback.evidence
            .take(6)
            .joinToString("\n") { item ->
                "- [${item.source}] ${item.ts}: ${item.text}"
            }
        val prompt = buildString {
            append("Rewrite this local LifeLog recap into a warm, natural answer.\n")
            append("Requirements:\n")
            append("- 2 to 4 sentences\n")
            append("- Mention only the strongest concrete signals\n")
            append("- If evidence is thin, say that naturally\n")
            append("- Do not invent missing details\n")
            append("- Do not mention logs, telemetry, internal fields, or system limitations unless necessary\n\n")
            append("Time period: $label\n\n")
            append("Evidence:\n")
            append(evidenceBlock)
            append("\n\n")
            append("Draft recap:\n")
            append(fallback.reply)
            append("\n\nFinal answer:")
        }
        return runCatching {
            val reply = runOnDevicePrompt(instruction, prompt).trim()
            if (reply.isBlank()) fallback else fallback.copy(
                reply = reply,
                mode = "local-llm",
                model = "gemma4-e4b-on-device"
            )
        }.getOrElse { fallback }
    }

    private fun buildRecapTakeaway(
        label: String,
        conversationCount: Int,
        locationCount: Int,
        reminderCount: Int,
        mealCount: Int,
        calendarCount: Int,
        activityMentions: List<String>,
    ): String {
        val period = when (label) {
            "today" -> "Today"
            "tonight" -> "Tonight"
            "yesterday" -> "Yesterday"
            "this week" -> "This week"
            else -> "Recently"
        }
        val parts = mutableListOf<String>()
        if (calendarCount > 0) parts += "you had calendar activity"
        if (locationCount > 0) parts += "you moved around a bit"
        if (conversationCount > 0) parts += "there was some conversation"
        if (mealCount > 0 || reminderCount > 0) parts += "there were a few reminders/check-ins"
        if (parts.isEmpty()) {
            return "$period looks pretty quiet from the logs I have, so this recap is based on a small amount of evidence."
        }
        val activityPhrase = activityMentions.firstOrNull()?.trim()?.trimEnd('.')
        return when {
            activityPhrase != null && locationCount > 0 -> "$period looked pretty light overall, but I can see you moved around some and one personal update was: $activityPhrase."
            activityPhrase != null -> "$period looks pretty light, but I do have one concrete anchor: $activityPhrase."
            locationCount > 0 -> "$period looked pretty steady overall, mostly with movement between places."
            else -> "$period looked fairly quiet from the logs I have."
        }
    }

    private fun cleanPrimaryUserMention(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (clean.isBlank()) return ""
        val lower = clean.lowercase()
        return when {
            lower.startsWith("for breakfast today i had ") -> clean.removePrefix("For breakfast today I had ").removePrefix("for breakfast today i had ") + " for breakfast"
            lower.startsWith("for lunch today i had ") -> clean.removePrefix("For lunch today I had ").removePrefix("for lunch today i had ") + " for lunch"
            lower.startsWith("for dinner today i had ") -> clean.removePrefix("For dinner today I had ").removePrefix("for dinner today i had ") + " for dinner"
            lower.startsWith("i had ") -> clean.removePrefix("I had ").removePrefix("i had ")
            lower.startsWith("i went ") -> clean.removePrefix("I ").removePrefix("i ")
            lower.startsWith("i was ") -> clean.removePrefix("I ").removePrefix("i ")
            lower.startsWith("i did ") -> clean.removePrefix("I ").removePrefix("i ")
            else -> clean
        }
    }

    private fun isUserActivityMessage(text: String): Boolean {
        val clean = text.trim()
        val lower = clean.lowercase()
        if (clean.length < 12) return false
        if (clean.endsWith("?")) return false
        if (isRecapPromptLike(lower)) return false
        val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstPerson = lower.startsWith("i ") || lower.startsWith("i'") || lower.contains(" i ") || lower.startsWith("we ") || lower.contains(" we ")
        return firstPerson && tokens.size >= 4
    }

    private fun isRecapPromptLike(text: String): Boolean {
        val lower = text.trim().lowercase()
        val asksRecap =
            lower.contains("recap") ||
                lower.contains("summary") ||
                lower.contains("what happened") ||
                lower.startsWith("how was") ||
                lower.startsWith("how were")
        val timeScoped =
            lower.contains("today") ||
                lower.contains("tonight") ||
                lower.contains("yesterday") ||
                lower.contains("week") ||
                lower.contains("day") ||
                lower.contains("night")
        return asksRecap && timeScoped
    }

    private fun isAssistantSummaryLike(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower.contains("here are the clearest highlights") ||
            lower.contains("i don't have much rich context") ||
            lower.contains("looked pretty light overall") ||
            lower.contains("looked pretty steady overall") ||
            lower.contains("use chat or live transcript occasionally") ||
            lower.contains("strongest signal i do have")
    }

    private fun recapEvidenceText(kindRaw: String, textRaw: String): String {
        val kind = kindRaw.trim().lowercase()
        val clean = humanizeLogText(kindRaw, textRaw).replace(Regex("\\s+"), " ").trim()
        return when (kind) {
            "location", "location_confirmed", "location_corrected" -> clean
                .removePrefix("Location ")
                .replace(Regex(" \\([^)]*\\)"), "")
                .replace(Regex("\\s*accuracy \\d+m", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Nearby location update" }
                .take(140)
            else -> clean.take(180)
        }
    }

    private fun isLowQualityTranscriptForRecap(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.length < 10) return true
        if (Regex("(.)\\1{5,}").containsMatchIn(clean)) return true
        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 3) return true
        if (tokens.toSet().size <= 2) return true
        val joined = tokens.joinToString(" ")
        return joined.contains("and then the") || joined == "the the" || joined == "and and then the other one is the other one is the other one is"
    }

    private suspend fun buildDirectPersonalMemoryAnswer(): ChatResult {
        val transcriptRows = phoneLogDao.getRecent(800)
            .filter { it.kind.equals("transcript", ignoreCase = true) || it.kind.equals("note", ignoreCase = true) }
            .sortedByDescending { parseTsMillis(it.ts) }

        val calendarRows = calendarEventDao.getAll(12)
            .sortedByDescending { parseTsMillis(it.startTs) }

        val reminderRows = phoneLogDao.getRecent(200)
            .filter {
                val kind = it.kind.lowercase()
                kind == "reminder" || kind == "meal_response" || kind == "location_confirmed" || kind == "location_corrected"
            }
            .sortedByDescending { parseTsMillis(it.ts) }

        val evidence = mutableListOf<ChatEvidenceItem>()
        val bullets = mutableListOf<String>()

        transcriptRows.take(6).forEach { row ->
            val clipped = row.text.trim().replace('\n', ' ').take(220)
            bullets += "- Transcript: ${formatEvidenceTimestamp(row.ts)}: $clipped"
            evidence += ChatEvidenceItem(row.ts, row.kind, clipped)
        }

        if (bullets.size < 5) {
            calendarRows.take(1).forEach { event ->
                val summary = listOf(event.title, event.location, event.notes)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                    .replace(Regex("https?://\\S+"), "")
                    .replace("Manage my ECAL", "", ignoreCase = true)
                    .replace("Buy Tickets", "", ignoreCase = true)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (summary.isNotBlank()) {
                    bullets += "- Calendar: ${formatEvidenceTimestamp(event.startTs)}: $summary"
                    evidence += ChatEvidenceItem(event.startTs, "calendar", summary)
                }
            }
        }

        reminderRows.take(2).forEach { row ->
            bullets += "- Reminder: ${formatEvidenceTimestamp(row.ts)}: ${row.text.take(160)}"
            evidence += ChatEvidenceItem(row.ts, row.kind, row.text.take(160))
        }

        if (bullets.isEmpty()) {
            return ChatResult(
                reply = "I don't have enough local transcript or memory history on this phone yet to answer that well.",
                mode = "local",
                model = "local-memory",
                reason = "No local transcript, note, calendar, or reminder rows found."
            )
        }

        return ChatResult(
            reply = buildString {
                append("Here is what I know from your local LifeLog history:\n")
                bullets.forEach { line ->
                    append(line)
                    append('\n')
                }
            }.trim(),
            mode = "local",
            intent = "summary",
            timeScope = "historical",
            model = "local-memory",
            reason = "Answered directly from local transcript-first memory on the phone.",
            evidence = evidence.take(10)
        )
    }

    private suspend fun chatOnDevice(text: String): ChatResult = withContext(Dispatchers.IO) {
        if (!isOnDeviceModelDownloaded()) {
            throw IllegalStateException("On-device model not downloaded yet — tap 'Download' in Settings")
        }
        liteRtMutex.withLock {
            try {
                doOnDeviceInference(text)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("session already exists", ignoreCase = true) ||
                    msg.contains("FAILED_PRECONDITION", ignoreCase = true)) {
                    // Stale session from a cancelled coroutine — reset engine and retry once
                    Log.w("LifeLogChat", "Stale session detected, resetting engine and retrying")
                    liteRtEngine = null
                    doOnDeviceInference(text)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun runOnDevicePrompt(systemPrompt: String, userPrompt: String): String {
        val engine = getOrCreateEngine()
        val reply = StringBuilder()
        engine.createConversation(
            com.google.ai.edge.litertlm.ConversationConfig(
                systemInstruction = if (systemPrompt.isNotEmpty()) {
                    com.google.ai.edge.litertlm.Contents.of(systemPrompt)
                } else null
            )
        ).use { conversation ->
            conversation.sendMessageAsync(userPrompt).collect { message ->
                reply.append(message.toString())
            }
        }
        return reply.toString().trim()
    }

    private suspend fun doOnDeviceInference(text: String): ChatResult {
        try {
            Log.i("LifeLogChat", "loading LiteRT-LM engine...")
            Log.i("LifeLogChat", "engine ready, building memory context")
            val systemPrompt = settingsRepository.cachedAssistantSystemPrompt.trim()
            val memory = buildLocalMemoryContext(text)
            Log.i("LifeLogChat", "memory_lines=${memory.lines().size} memory_chars=${memory.length}")
            if (memory.isNotEmpty()) Log.i("LifeLogChat", "memory_preview=${memory.take(400)}")
            // Memory goes only in the user turn — not in system instruction — to avoid duplication.
            val instruction = systemPrompt
            val userPrompt = buildString {
                if (memory.isNotEmpty()) {
                    append(memory)
                    append("\n\n")
                    append("Answer using the LifeLog memory above when relevant. ")
                    append("Do not claim you lack access to the user's personal life if the memory contains relevant evidence.\n\n")
                }
                append(text)
            }
            val textReply = runOnDevicePrompt(instruction, userPrompt)
            Log.i("LifeLogChat", "reply=${textReply.take(500)}")
            if (textReply.isEmpty()) throw IllegalStateException("empty_reply")
            return ChatResult(reply = textReply, mode = "llm", model = "gemma4-e4b-on-device")
        } catch (e: Exception) {
            Log.e("LifeLogChat", "chatOnDevice error: ${e.javaClass.simpleName}: ${e.message}", e)
            throw IllegalStateException(
                "On-device model error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim()
            )
        }
    }

    fun isOnDeviceModelDownloaded(): Boolean =
        java.io.File(context.filesDir, ON_DEVICE_MODEL_FILE).exists()

    /** Pre-warm the LiteRT engine in the background so first-query latency is inference-only. */
    fun warmUpOnDeviceEngine() {
        if (!isOnDeviceModelDownloaded()) return
        val provider = settingsRepository.cachedAiProvider.trim().lowercase()
        if (provider != "on-device") return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                liteRtMutex.withLock { getOrCreateEngine() }
                Log.i("LifeLogChat", "engine pre-warmed")
            }.onFailure { Log.w("LifeLogChat", "engine warm-up failed: ${it.message}") }
        }
    }

    /**
     * Silently download Whisper model files in the background on first launch.
     * Shows a status bar progress notification while downloading.
     * No-op if already downloaded. Safe to call from Service.onCreate().
     */
    fun ensureWhisperDownloaded() {
        if (whisperEngine.isReady()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "whisper_download"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "Model Downloads", android.app.NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Background download of AI model files" }
            )
        }
        val notifId = 9201
        fun buildNotif(pct: Int, done: Boolean = false) =
            androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("LifeLog: Downloading voice model")
                .setContentText(if (done) "Voice transcription ready" else "Whisper STT — $pct%")
                .setProgress(100, pct, false)
                .setOngoing(!done)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()

        nm.notify(notifId, buildNotif(0))
        Log.i("LifeLogChat", "Whisper model not present — downloading in background (~82 MB)")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            downloadWhisper { pct ->
                nm.notify(notifId, buildNotif(pct))
            }.onFailure {
                Log.w("LifeLogChat", "Whisper background download failed: ${it.message}")
                nm.cancel(notifId)
            }.onSuccess {
                Log.i("LifeLogChat", "Whisper download complete — on-device STT now available")
                nm.notify(notifId, buildNotif(100, done = true))
                kotlinx.coroutines.delay(4000)
                nm.cancel(notifId)
            }
        }
    }

    fun onDeviceModelSizeMb(): Int = ON_DEVICE_MODEL_SIZE_MB

    suspend fun downloadOnDeviceModel(
        hfToken: String = "",
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = java.io.File(context.filesDir, ON_DEVICE_MODEL_FILE)
            val tempFile = java.io.File(context.filesDir, "$ON_DEVICE_MODEL_FILE.tmp")
            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(3600, TimeUnit.SECONDS)
                .build()
            val reqBuilder = Request.Builder().url(ON_DEVICE_MODEL_URL)
            if (hfToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $hfToken")
            downloadClient.newCall(reqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val hint = when (res.code) {
                        401, 403 -> " Accept the Gemma license on Hugging Face and use a valid read token."
                        404 -> " Model URL not found."
                        else -> ""
                    }
                    throw IllegalStateException("Download failed (HTTP ${res.code}).$hint ${body.take(200)}".trim())
                }
                val total = res.body?.contentLength() ?: -1L
                var downloaded = 0L
                res.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) withContext(Dispatchers.Main) {
                                onProgress(((downloaded * 100L) / total).toInt())
                            }
                        }
                    }
                }
                tempFile.renameTo(modelFile)
                withContext(Dispatchers.Main) { onProgress(100) }
            }
        }
    }

    private fun humanizeLocationText(raw: String, cache: MutableMap<String, String>): String {
        val coords = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)").find(raw)
        if (coords == null) return raw
        val lat = coords.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return raw
        val lon = coords.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return raw
        val key = "${"%.4f".format(lat)},${"%.4f".format(lon)}"
        val place = cache[key] ?: reverseGeocodeLabel(lat, lon).also { cache[key] = it }
        return if (place.isNotBlank()) {
            raw.replace(coords.value, "$place ($key)")
        } else {
            raw
        }
    }

    private fun humanizeLogText(kindRaw: String, textRaw: String): String {
        val kind = kindRaw.trim().lowercase()
        val text = textRaw.trim()
        if (text.isEmpty()) return text

        if (kind == "location" || kind == "location_confirmed" || kind == "location_corrected") {
            return humanizeLocationText(text, mutableMapOf())
        }

        if (text.startsWith("{") && text.endsWith("}")) {
            return try {
                val obj = JSONObject(text)
                when (obj.optString("type", "").trim().lowercase()) {
                    "meal_prompt_followup" -> {
                        val meal = obj.optString("meal", "meal")
                        val idx = obj.optInt("followup_index", 0)
                        if (idx > 0) "Meal follow-up #$idx for $meal" else "Meal follow-up for $meal"
                    }
                    "meal_response" -> {
                        val meal = obj.optString("meal", "meal")
                        val value = obj.optString("value", "").ifBlank { obj.optString("response", "") }
                        if (value.isNotBlank()) "$meal response: $value" else "$meal response recorded"
                    }
                    else -> text
                }
            } catch (_: Exception) {
                text
            }
        }

        return text
    }

    private fun reverseGeocodeLabel(lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(context)
            val list = geocoder.getFromLocation(lat, lon, 1)
            val addr = list?.firstOrNull() ?: return ""
            // Build "123 Main St, St. Louis, MO" — featureName alone is just the street number
            val street = listOfNotNull(addr.subThoroughfare, addr.thoroughfare)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { addr.featureName ?: "" }
            listOf(street, addr.locality, addr.adminArea)
                .filter { !it.isNullOrBlank() }
                .joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectRecapScope(query: String): String? {
        val asksRecap =
            query.contains("recap") ||
            query.contains("summary") ||
            query.contains("what happened") ||
            (query.contains("how") && (query.contains("my day") || query.contains("my night") || query.contains("today") || query.contains("week") || query.contains("yesterday")))

        if (!asksRecap) return null

        return when {
            query.contains("tonight") || query.contains("my night") || query.contains("night") -> "tonight"
            query.contains("evening") -> "evening"
            query.contains("this week") || query.contains("my week") || query.contains("week") -> "this week"
            query.contains("yesterday") -> "yesterday"
            else -> "today"
        }
    }

    fun isSpeakerModelDownloaded(): Boolean =
        java.io.File(context.filesDir, SPEAKER_MODEL_FILE).exists()

    fun speakerModelSizeMb(): Int = SPEAKER_MODEL_SIZE_MB

    suspend fun downloadSpeakerModel(
        modelUrl: String = "",
        hfToken: String = "",
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = java.io.File(context.filesDir, SPEAKER_MODEL_FILE)
            val tempFile = java.io.File(context.filesDir, "$SPEAKER_MODEL_FILE.tmp")
            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(1800, TimeUnit.SECONDS)
                .build()
            val attempts = buildList {
                val user = modelUrl.trim()
                if (user.isNotBlank()) add(user)
                add(SPEAKER_MODEL_URL)
                add("https://huggingface.co/Xenova/wavlm-base-plus-sv/resolve/main/onnx/model.onnx")
                add("https://huggingface.co/Xenova/wavlm-base-plus-sv/resolve/main/model.onnx")
                add("https://huggingface.co/Xenova/wavlm-base-plus-sv/resolve/main/model.onnx?download=true")
            }
            var lastError = "Unknown download error"
            var success = false
            for (url in attempts.distinct()) {
                val reqBuilder = Request.Builder().url(url)
                    .header("User-Agent", "LifeLog-Android/1.0")
                if (hfToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $hfToken")
                val res = downloadClient.newCall(reqBuilder.build()).execute()
                res.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string().orEmpty()
                        lastError = "HTTP ${it.code}: ${body.take(120)}"
                    } else {
                        val total = it.body?.contentLength() ?: -1L
                        var downloaded = 0L
                        it.body?.byteStream()?.use { input ->
                            tempFile.outputStream().use { output ->
                                val buf = ByteArray(65536)
                                var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    output.write(buf, 0, n)
                                    downloaded += n
                                    if (total > 0) withContext(Dispatchers.Main) {
                                        onProgress(((downloaded * 100L) / total).toInt())
                                    }
                                }
                            }
                        }
                        if (tempFile.length() > 1024L) {
                            if (!tempFile.renameTo(modelFile)) {
                                tempFile.copyTo(modelFile, overwrite = true)
                                tempFile.delete()
                            }
                            success = true
                            withContext(Dispatchers.Main) { onProgress(100) }
                        } else {
                            lastError = "Downloaded file was too small"
                            tempFile.delete()
                        }
                    }
                }
                if (success) break
            }
            if (!success) {
                throw IllegalStateException(
                    "Speaker model download failed. The app still works without this. Error: $lastError"
                )
            }
        }
    }

    private suspend fun buildLocalMemoryContext(query: String): String {
        val cleanQuery = query.trim()
        val normalizedQuery = cleanQuery.lowercase()
        val queryTerms = cleanQuery
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .flatMap { term ->
                buildList {
                    add(term)
                    if (term.endsWith("s") && term.length > 3) add(term.dropLast(1))
                    if (term.endsWith("ing") && term.length > 5) add(term.dropLast(3))
                }
            }
            .distinct()
            .take(6)
        val wantsLocation = normalizedQuery.contains("where") || normalizedQuery.contains("location") || normalizedQuery.contains("been")
        val wantsReminder = normalizedQuery.contains("reminder") || normalizedQuery.contains("prompt") || normalizedQuery.contains("notification")
        val wantsCalendar = normalizedQuery.contains("calendar") || normalizedQuery.contains("schedule") || normalizedQuery.contains("event")
        val wantsRecency = normalizedQuery.contains("today") || normalizedQuery.contains("recent") || normalizedQuery.contains("latest") || normalizedQuery.contains("week")
        val wantsPersonal = normalizedQuery.contains("personal") || normalizedQuery.contains("about me") || normalizedQuery.contains("my life")

        val factHits = linkedMapOf<Long, String>()
        if (queryTerms.isEmpty()) {
            factDao.getAll().take(12).forEach { factHits[it.id] = formatMemoryLine("fact", it.timestamp, it.text) }
        } else {
            queryTerms.forEach { term ->
                factDao.search(term).take(6).forEach { factHits[it.id] = formatMemoryLine("fact", it.timestamp, it.text) }
            }
            factDao.getAll().take(if (wantsRecency) 8 else 4).forEach {
                factHits.putIfAbsent(it.id, formatMemoryLine("fact", it.timestamp, it.text))
            }
        }

        // Skip low-value assistant filler messages — they waste context and confuse the model.
        val fillerPhrases = setOf(
            "what can i help you with",
            "how can i help you",
            "is there anything else",
            "let me know if you need",
        )
        fun isFillerMessage(text: String): Boolean {
            val lower = text.trim().lowercase()
            return lower.length < 80 && fillerPhrases.any { lower.contains(it) }
        }

        val messageHits = linkedMapOf<Long, String>()
        if (queryTerms.isEmpty()) {
            messageDao.getRecent(16).filterNot { isFillerMessage(it.text) }.forEach {
                messageHits[it.id] = formatMemoryLine(it.role, it.timestamp, it.text)
            }
        } else {
            queryTerms.forEach { term ->
                messageDao.search(term, 8).filterNot { isFillerMessage(it.text) }.forEach {
                    messageHits[it.id] = formatMemoryLine(it.role, it.timestamp, it.text)
                }
            }
            messageDao.getRecent(8).filterNot { isFillerMessage(it.text) }.forEach {
                messageHits.putIfAbsent(it.id, formatMemoryLine(it.role, it.timestamp, it.text))
            }
        }

        val logHits = linkedMapOf<Long, String>()
        // Fetch transcripts and notes directly by kind — avoids them getting buried by
        // heartbeat/battery/wifi/location noise rows in getRecent().
        val transcriptLimit = when {
            wantsPersonal -> 200
            queryTerms.isNotEmpty() -> 150
            else -> 80
        }
        val transcriptCandidates = (
            phoneLogDao.getByKind("transcript", transcriptLimit) +
            phoneLogDao.getByKind("note", 40)
        ).filter { !isUselessTranscript(it.text) }
         .sortedByDescending { scoreLogRelevance(it, queryTerms, wantsPersonal) }

        transcriptCandidates.take(if (wantsPersonal) 12 else 8).forEach {
            logHits[it.id] = formatMemoryLine(it.kind.ifBlank { "log" }, parseTsMillis(it.ts), it.text)
        }

        // Add location/reminder rows only when relevant
        if (wantsLocation) {
            phoneLogDao.getByKind("location", 8).forEach {
                logHits.putIfAbsent(it.id, formatMemoryLine("location", parseTsMillis(it.ts), it.text))
            }
        }
        if (wantsReminder) {
            phoneLogDao.getByKind("reminder", 8).forEach {
                logHits.putIfAbsent(it.id, formatMemoryLine("reminder", parseTsMillis(it.ts), it.text))
            }
        }

        // Keyword search across all kinds as supplemental hits
        if (queryTerms.isNotEmpty()) {
            queryTerms.forEach { term ->
                phoneLogDao.search(term, 8).filter {
                    it.kind.lowercase() in setOf("transcript", "note", "location", "reminder", "meal_response")
                }.forEach {
                    logHits.putIfAbsent(it.id, formatMemoryLine(it.kind.ifBlank { "log" }, parseTsMillis(it.ts), it.text))
                }
            }
        }

        val calendarHits = linkedMapOf<Long, String>()
        if (queryTerms.isEmpty()) {
            calendarEventDao.getAll(10).forEach {
                val summary = listOf(it.title, it.location, it.notes).filter { part -> part.isNotBlank() }.joinToString(" | ")
                calendarHits[it.id] = formatMemoryLine("event", parseTsMillis(it.startTs), summary)
            }
        } else {
            queryTerms.forEach { term ->
                calendarEventDao.search(term, 6).forEach {
                    val summary = listOf(it.title, it.location, it.notes).filter { part -> part.isNotBlank() }.joinToString(" | ")
                    calendarHits[it.id] = formatMemoryLine("event", parseTsMillis(it.startTs), summary)
                }
            }
            calendarEventDao.getAll(if (wantsCalendar || wantsRecency) 10 else 4).forEach {
                val summary = listOf(it.title, it.location, it.notes).filter { part -> part.isNotBlank() }.joinToString(" | ")
                calendarHits.putIfAbsent(it.id, formatMemoryLine("event", parseTsMillis(it.startTs), summary))
            }
        }

        val routineHits = linkedMapOf<Long, String>()
        if (queryTerms.isEmpty()) {
            routineDao.getAll(10).forEach {
                val summary = listOf(it.title, it.note, it.display).filter { part -> part.isNotBlank() }.joinToString(" | ")
                routineHits[it.id] = formatMemoryLine("routine", parseTsMillis(it.updatedAt), summary)
            }
        } else {
            queryTerms.forEach { term ->
                routineDao.search(term, 6).forEach {
                    val summary = listOf(it.title, it.note, it.display).filter { part -> part.isNotBlank() }.joinToString(" | ")
                    routineHits[it.id] = formatMemoryLine("routine", parseTsMillis(it.updatedAt), summary)
                }
            }
            routineDao.getAll(if (wantsRecency) 8 else 4).forEach {
                val summary = listOf(it.title, it.note, it.display).filter { part -> part.isNotBlank() }.joinToString(" | ")
                routineHits.putIfAbsent(it.id, formatMemoryLine("routine", parseTsMillis(it.updatedAt), summary))
            }
        }

        val evidence = buildList {
            addAll(factHits.values.take(6))
            addAll(messageHits.values.take(6))
            addAll(logHits.values.take(14))
            addAll(calendarHits.values.take(6))
            addAll(routineHits.values.take(4))
        }.take(24)

        if (evidence.isEmpty()) return ""

        // Gemma 4 E4B has an 8192-token context window. Keep memory block under ~3200 chars
        // so there's room for the system prompt, query, and response.
        val header = "LifeLog memory (use when relevant; do not claim lack of access if evidence is present):\n"
        val sb = StringBuilder(header)
        for (line in evidence) {
            // Truncate each evidence line to 220 chars
            val truncated = if (line.length > 220) line.take(217) + "…" else line
            val candidate = "- $truncated\n"
            if (sb.length + candidate.length > 3200) break
            sb.append(candidate)
        }
        return sb.toString().trim()
    }

    private fun isUselessTranscript(text: String): Boolean {
        val clean = text.trim().lowercase().replace(Regex("^[\\s\\[]+|[\\s\\]]+$"), "").trim()
        val full  = text.trim().lowercase()
        val knownTags = setOf(
            "silence", "music", "laughter", "noise", "inaudible",
            "applause", "music playing", "background noise", "ambient noise"
        )
        if (clean in knownTags) return true
        if (knownTags.any { full.contains("[$it]") || full.contains("[ $it]") }) return true
        // Also drop very short fragments that are just noise
        if (text.trim().length < 4) return true
        return false
    }

    private fun scoreLogRelevance(
        row: PhoneLogEntity,
        queryTerms: List<String>,
        wantsPersonal: Boolean
    ): Int {
        val text = row.text.lowercase()
        var score = 0
        if (row.kind.equals("transcript", ignoreCase = true)) score += 5
        if (row.kind.equals("note", ignoreCase = true)) score += 4
        if (wantsPersonal) score += 3
        queryTerms.forEach { term ->
            if (text.contains(term)) score += 4
        }
        if (text.contains("michael")) score += 2
        if (text.contains("i ") || text.contains(" i'm ") || text.contains(" my ")) score += 1
        // Recency bonus: +6 for today, +3 for last 48h, +1 for last week
        val ageMs = System.currentTimeMillis() - parseTsMillis(row.ts)
        score += when {
            ageMs < 24L * 3600L * 1000L -> 6
            ageMs < 48L * 3600L * 1000L -> 3
            ageMs < 7L * 24L * 3600L * 1000L -> 1
            else -> 0
        }
        return score
    }

    private fun formatMemoryLine(kind: String, timestamp: Long, text: String): String {
        val cleanText = text.replace(Regex("\\s+"), " ").trim().take(300)
        val ts = try {
            java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .toString()
        } catch (_: Exception) {
            timestamp.toString()
        }
        return "[$kind @ $ts] $cleanText"
    }

    private fun formatEvidenceTimestamp(raw: String): String {
        val ts = parseTsMillis(raw)
        return formatEvidenceTimestampFromMillis(ts).ifBlank { raw }
    }

    private fun formatEvidenceTimestampFromMillis(ts: Long): String {
        return try {
            java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .toString()
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun syncLocalMirror(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val facts = getFacts(baseUrl = baseUrl, limit = 240).getOrElse { throw it }
            val logs = getPhoneLogs(baseUrl = baseUrl, limit = 240).getOrElse { throw it }
            val calendar = getCalendarEvents(baseUrl = baseUrl, limit = 260).getOrElse { throw it }
            val routines = getRoutines(baseUrl = baseUrl, limit = 260, detect = false).getOrElse { throw it }

            factDao.clearAll()
            factDao.insertAll(
                facts.map {
                    FactEntity(
                        id = it.id,
                        text = it.fact,
                        timestamp = parseTsMillis(it.createdAt)
                    )
                }
            )

            phoneLogDao.clearAll()
            phoneLogDao.insertAll(
                logs.map {
                    PhoneLogEntity(
                        id = it.id,
                        ts = it.ts,
                        deviceId = it.deviceId,
                        kind = it.kind,
                        text = it.text,
                        transcriptId = it.transcriptId,
                        sourceType = it.sourceType,
                        speakerId = it.speakerId,
                        speakerClusterId = it.speakerClusterId,
                        speakerConfidence = it.speakerConfidence,
                        tags = it.tags,
                        importance = it.importance,
                        mediaLikelihood = it.mediaLikelihood,
                        dialogDensity = it.dialogDensity,
                        source = it.source
                    )
                }
            )

            calendarEventDao.clearAll()
            calendarEventDao.insertAll(
                calendar.map {
                    CalendarEventEntity(
                        id = it.id,
                        source = it.source,
                        remoteId = it.remoteId,
                        calendarId = it.calendarId,
                        title = it.title,
                        startTs = it.startTs,
                        endTs = it.endTs,
                        timezone = it.timezone,
                        recurrenceRule = it.recurrenceRule,
                        location = it.location,
                        notes = it.notes,
                        isAllDay = it.isAllDay,
                        status = it.status,
                        updatedAt = it.updatedAt,
                        display = it.display
                    )
                }
            )

            routineDao.clearAll()
            routineDao.insertAll(
                routines.map {
                    RoutineEntity(
                        id = it.id,
                        title = it.title,
                        kind = it.kind,
                        anchorKey = it.anchorKey,
                        hourBucket = it.hourBucket,
                        weekdays = it.weekdays,
                        note = it.note,
                        confidence = it.confidence,
                        source = it.source,
                        active = it.active,
                        occurrences = it.occurrences,
                        firstSeenTs = it.firstSeenTs,
                        lastSeenTs = it.lastSeenTs,
                        updatedAt = it.updatedAt,
                        createdAt = it.createdAt,
                        display = it.display
                    )
                }
            )
        }
    }

    private fun parseTsMillis(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return System.currentTimeMillis()
        return try {
            try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                val normalized = value.replace('T', ' ')
                val patterns = listOf(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                )
                patterns.firstNotNullOfOrNull { formatter ->
                    runCatching {
                        java.time.LocalDateTime.parse(normalized, formatter)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    }.getOrNull()
                } ?: System.currentTimeMillis()
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ── Ollama model management ──────────────────────────────────────────

    data class OllamaModelInfo(val name: String, val size: Long = 0L)

    data class OllamaPullProgress(
        val status: String,
        val completed: Long = 0L,
        val total: Long = 0L
    ) {
        val percent: Int get() = if (total > 0L) ((completed * 100L) / total).toInt() else -1
        val isDone: Boolean get() = status == "success"
        val isFailed: Boolean get() = status.startsWith("error", ignoreCase = true)
    }

    suspend fun ollamaListModels(): Result<List<OllamaModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = settingsRepository.ollamaUrl.trim().trimEnd('/')
            val tempClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("$url/api/tags").get().build()
            tempClient.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("Ollama not reachable (HTTP ${res.code})")
                val arr = JSONObject(body).optJSONArray("models") ?: return@use emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(OllamaModelInfo(name = obj.optString("name", ""), size = obj.optLong("size", 0L)))
                    }
                }
            }
        }
    }

    suspend fun ollamaPullModel(
        modelName: String,
        onProgress: (OllamaPullProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = settingsRepository.ollamaUrl.trim().trimEnd('/')
            val payload = JSONObject().put("model", modelName).put("stream", true)
            val pullClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("$url/api/pull")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            pullClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Pull failed (HTTP ${res.code})")
                val reader = res.body?.charStream()?.buffered()
                    ?: throw IllegalStateException("No response body")
                reader.use {
                    for (line in it.lineSequence()) {
                        if (line.isBlank()) continue
                        try {
                            val obj = JSONObject(line)
                            val prog = OllamaPullProgress(
                                status = obj.optString("status", ""),
                                completed = obj.optLong("completed", 0L),
                                total = obj.optLong("total", 0L)
                            )
                            withContext(Dispatchers.Main) { onProgress(prog) }
                            if (prog.isDone || prog.isFailed) break
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    suspend fun getInsights(): InsightsResponse = withContext(Dispatchers.IO) {
        val baseUrl = settingsRepository.cachedLifeLogSyncUrl
            .ifBlank { settingsRepository.lifeLogSyncUrl }
            .trim()
            .trimEnd('/')
        val url = "${baseUrl}/phone/insights"
        val request = Request.Builder()
            .url(url)
            .addHeader(
                "Authorization",
                "Bearer ${settingsRepository.cachedLifeLogSyncToken.ifBlank { settingsRepository.lifeLogSyncToken }}"
            )
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            val ok = json.optBoolean("ok", false)
            
            if (!ok) {
                throw Exception("API returned error: $responseBody")
            }

            val insightsJson = json.optJSONObject("insights") ?: JSONObject()
            val insights = mutableMapOf<String, List<Insight>>()
            
            val keys = insightsJson.keys()
            for (key in keys) {
                val itemsArray = insightsJson.optJSONArray(key) ?: continue
                val items = mutableListOf<Insight>()
                
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.optJSONObject(i) ?: continue
                    items.add(
                        Insight(
                            type = item.optString("type", ""),
                            time = item.optString("time", "").ifBlank { null },
                            text = item.optString("text", ""),
                            title = item.optString("title", "").ifBlank { null },
                            eventTime = item.optString("when", "").ifBlank { null },
                            location = item.optString("location", "").ifBlank { null }
                        )
                    )
                }
                insights[key] = items
            }
            
            InsightsResponse(ok = true, insights = insights)
        }
    }
}
