package com.lifelog.phone.data.remote

import android.net.Uri
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.Insight
import com.lifelog.phone.InsightsResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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

@Singleton
class LifeLogApi @Inject constructor(
    val settingsRepository: SettingsRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun authHeader(req: Request.Builder): Request.Builder {
        val token = settingsRepository.cachedToken.ifBlank { settingsRepository.token }.trim()
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
            val transcribeReq = authHeader(Request.Builder())
                .url("${base(baseUrl)}/phone/upload_wav_query")
                .post(audioWav.toRequestBody("audio/wav".toMediaType()))
                .build()

            val transcriptResult = client.newCall(transcribeReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Transcription failed (HTTP ${res.code})")
                }
                val obj = JSONObject(body)
                val text = obj.optString("text", "").trim()
                val stem = obj.optString("query_stem", "").trim()
                if (text.isEmpty()) {
                    throw IllegalStateException(obj.optString("error", "transcription_failed"))
                }
                text to stem
            }

            val chatRes = chatInternal(baseUrl = baseUrl, text = transcriptResult.first, voice = true, sessionId = sessionId)
                .getOrElse { throw it }
            
            chatRes to transcriptResult.second
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
                val payload = JSONObject()
                    .put("text", text)
                    .put("voice", voice)
                val systemPrompt = settingsRepository.cachedAssistantSystemPrompt.trim()
                if (systemPrompt.isNotEmpty()) {
                    payload.put("system_prompt", systemPrompt)
                }
                if (sessionId.isNotBlank()) {
                    payload.put("session_id", sessionId.trim())
                }

                val req = authHeader(Request.Builder())
                    .url("${base(baseUrl)}/chat")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        throw IllegalStateException("Chat failed (HTTP ${res.code})")
                    }
                    val obj = JSONObject(body)
                    val mode = obj.optString("mode", "").trim()
                    val reply = obj.optString("reply", "").trim()
                    val debug = obj.optJSONObject("debug")
                    val evidenceArr = obj.optJSONArray("evidence")
                    val evidence = buildList {
                        if (evidenceArr != null) {
                            for (i in 0 until evidenceArr.length()) {
                                val row = evidenceArr.optJSONObject(i) ?: continue
                                val txt = row.optString("text", "").trim()
                                if (txt.isEmpty()) continue
                                add(
                                    ChatEvidenceItem(
                                        ts = row.optString("ts", ""),
                                        source = row.optString("source", ""),
                                        text = txt
                                    )
                                )
                            }
                        }
                    }
                    if (reply.isEmpty() && mode != "asr_noise_filtered") {
                        throw IllegalStateException("empty_reply")
                    }
                    ChatResult(
                        reply = reply,
                        mode = mode,
                        intent = debug?.optString("intent", "") ?: "",
                        timeScope = debug?.optString("time_scope", "") ?: "",
                        windowStart = debug?.optString("window_start", "") ?: "",
                        windowEnd = debug?.optString("window_end", "") ?: "",
                        model = debug?.optString("model", "") ?: "",
                        reason = debug?.optString("reason", "") ?: "",
                        evidence = evidence,
                        replyStem = obj.optString("reply_stem", "")
                    )
                }
            }
        }

    suspend fun getInsights(): InsightsResponse = withContext(Dispatchers.IO) {
        val baseUrl = settingsRepository.baseUrl.trim().trimEnd('/')
        val url = "${baseUrl}/phone/insights"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settingsRepository.token}")
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
