package com.lifelog.phone.presentation.dashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.phone.data.Fact
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.CalendarEventItem
import com.lifelog.phone.data.remote.CalendarSyncEvent
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.data.remote.PhoneLogEvent
import com.lifelog.phone.data.remote.RoutineItem
import com.lifelog.phone.data.remote.RoutineSyncEvent
import com.lifelog.phone.data.remote.DataQuestionItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.text.ParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogTypeOption(
    val key: String,
    val label: String,
    val count: Int
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val api: LifeLogApi,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val TAG = "DashboardViewModel"
    private val stateKinds = setOf("battery", "wifi", "network", "app_usage", "heartbeat")
    private val lowSignalKinds = setOf("battery", "wifi", "network", "app_usage", "heartbeat")
    private val logKindPriority = listOf(
        "location",
        "transcript",
        "browser_history",
        "note",
        "conversation",
        "voice",
        "reminder",
        "battery",
        "wifi",
        "network",
        "app_usage",
        "heartbeat"
    )

    private val _facts = MutableStateFlow<List<Fact>>(emptyList())
    val facts: StateFlow<List<Fact>> = _facts.asStateFlow()

    private val _logs = MutableStateFlow<List<PhoneLogEvent>>(emptyList())
    val logs: StateFlow<List<PhoneLogEvent>> = _logs.asStateFlow()

    private val _calendarEvents = MutableStateFlow<List<CalendarEventItem>>(emptyList())
    val calendarEvents: StateFlow<List<CalendarEventItem>> = _calendarEvents.asStateFlow()

    private val _routines = MutableStateFlow<List<RoutineItem>>(emptyList())
    val routines: StateFlow<List<RoutineItem>> = _routines.asStateFlow()

    private val _qaItems = MutableStateFlow<List<DataQuestionItem>>(emptyList())
    val qaItems: StateFlow<List<DataQuestionItem>> = _qaItems.asStateFlow()

    private val _isLoadingFacts = MutableStateFlow(false)
    val isLoadingFacts: StateFlow<Boolean> = _isLoadingFacts.asStateFlow()

    private val _isLoadingLogs = MutableStateFlow(false)
    val isLoadingLogs: StateFlow<Boolean> = _isLoadingLogs.asStateFlow()

    private val _isLoadingCalendar = MutableStateFlow(false)
    val isLoadingCalendar: StateFlow<Boolean> = _isLoadingCalendar.asStateFlow()

    private val _isLoadingRoutines = MutableStateFlow(false)
    val isLoadingRoutines: StateFlow<Boolean> = _isLoadingRoutines.asStateFlow()

    private val _isLoadingQuestions = MutableStateFlow(false)
    val isLoadingQuestions: StateFlow<Boolean> = _isLoadingQuestions.asStateFlow()

    private val _logTypeOptions = MutableStateFlow<List<LogTypeOption>>(emptyList())
    val logTypeOptions: StateFlow<List<LogTypeOption>> = _logTypeOptions.asStateFlow()

    private val _selectedLogType = MutableStateFlow("highlights")
    val selectedLogType: StateFlow<String> = _selectedLogType.asStateFlow()

    private val _speakerOptions = MutableStateFlow<List<String>>(emptyList())
    val speakerOptions: StateFlow<List<String>> = _speakerOptions.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private var currentBaseUrl: String = ""
    private var allFacts: List<Fact> = emptyList()
    private var allLogs: List<PhoneLogEvent> = emptyList()
    private var allCalendarEvents: List<CalendarEventItem> = emptyList()
    private var allRoutines: List<RoutineItem> = emptyList()
    private var serverSpeakerNames: List<String> = emptyList()
    private var currentLogQuery: String = ""
    private var currentCalendarQuery: String = ""
    private var currentRoutineQuery: String = ""
    private var mediaPlayer: MediaPlayer? = null

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun playAudio(stem: String, timestamp: String = "") {
        val baseUrl = settingsRepository.cachedBaseUrl.ifBlank { settingsRepository.baseUrl }
        val token = settingsRepository.cachedToken.ifBlank { settingsRepository.token }
        if (baseUrl.isBlank() || stem.isBlank()) {
            Log.e(TAG, "playAudio: missing baseUrl=$baseUrl or stem=$stem")
            _error.value = "Missing server URL"
            return
        }

        val encodedStem = Uri.encode(stem)
        val url = if (baseUrl.endsWith("/")) {
            "${baseUrl}audio/$encodedStem"
        } else {
            "$baseUrl/audio/$encodedStem"
        }

        Log.i(TAG, "playAudio: baseUrl=$baseUrl, stem=$stem, token=${token.take(10)}..., url=$url")

        val offsetMs = calculateOffset(stem, timestamp)

        viewModelScope.launch {
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    // Download audio file first using OkHttp (which includes auth headers)
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("X-Phone-Token", token)
                        .build()

                    Log.i(TAG, "playAudio: downloading from $url with auth headers")
                    val response = client.newCall(req).execute()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "playAudio: HTTP error ${response.code}")
                        _error.value = "Server error: ${response.code}"
                        return@withContext null
                    }

                    val audioData = response.body?.bytes()
                    if (audioData == null || audioData.isEmpty()) {
                        Log.e(TAG, "playAudio: empty response")
                        _error.value = "No audio data received"
                        return@withContext null
                    }

                    // Save to temp file
                    val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
                    file.writeBytes(audioData)
                    file
                }

                if (tempFile == null) return@launch

                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) it.stop()
                    } catch (_: Exception) {}
                    it.release()
                }

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)
                    prepareAsync()
                    setOnPreparedListener {
                        Log.i(TAG, "MediaPlayer: prepared, starting at $offsetMs ms")
                        if (offsetMs > 0) {
                            it.seekTo(offsetMs.toInt())
                        }
                        it.start()
                    }
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer == it) mediaPlayer = null
                        try { tempFile.delete() } catch (_: Exception) {}
                    }
                    setOnErrorListener { innerMp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        _error.value = "Playback error: $what/$extra"
                        innerMp.release()
                        if (mediaPlayer == innerMp) mediaPlayer = null
                        try { tempFile.delete() } catch (_: Exception) {}
                        true
                    }
                }
                mediaPlayer = mp
            } catch (e: Exception) {
                Log.e(TAG, "playAudio exception: ${e.javaClass.simpleName}", e)
                _error.value = "Error: ${e.javaClass.simpleName} - ${e.message ?: e.cause?.message ?: "Unknown error"}"
            }
        }
    }

    private fun calculateOffset(stem: String, timestamp: String): Long {
        if (timestamp.isBlank() || !stem.startsWith("SPRINT_")) return 0L
        
        try {
            val parts = stem.split("_")
            if (parts.size < 3) return 0L
            val stemTimeStr = "${parts[1]}_${parts[2]}"
            val stemFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val stemDate = stemFmt.parse(stemTimeStr) ?: return 0L
            
            val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val tsDate = tsFmt.parse(timestamp.take(16)) ?: return 0L
            
            val diffMs = tsDate.time - stemDate.time
            return if (diffMs > 0) diffMs else 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating offset", e)
            return 0L
        }
    }

    fun loadFacts(baseUrl: String) {
        if (baseUrl.isBlank()) return
        currentBaseUrl = baseUrl.trim().trimEnd('/')
        viewModelScope.launch {
            _isLoadingFacts.value = true
            api.getFacts(baseUrl = baseUrl, limit = 240)
                .onSuccess { items ->
                    allFacts = items.map { item ->
                        Fact(
                            id = item.id,
                            text = item.fact.ifBlank { "(empty fact)" },
                            timestamp = parseTimestamp(item.createdAt)
                        )
                    }.sortedByDescending { it.timestamp }
                    _facts.value = allFacts
                }
                .onFailure { e ->
                    _error.value = "Failed to load facts: ${e.message}"
                }
            _isLoadingFacts.value = false
        }
    }

    fun searchFacts(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            _facts.value = allFacts
            return
        }
        _facts.value = allFacts.filter { row -> row.text.lowercase().contains(q) }
    }

    fun loadLogs(baseUrl: String) {
        if (baseUrl.isBlank()) return
        currentBaseUrl = baseUrl.trim().trimEnd('/')
        viewModelScope.launch {
            _isLoadingLogs.value = true
            api.getPhoneLogs(baseUrl = baseUrl, limit = 180)
                .onSuccess { items ->
                    allLogs = compressStateChanges(items.sortedByDescending { it.ts })
                    refreshSpeakerOptionsFromServer(baseUrl)
                    rebuildLogTypeOptions()
                    rebuildSpeakerOptions()
                    applyLogFilters()
                }
                .onFailure { e ->
                    _error.value = "Failed to load logs: ${e.message}"
                }
            _isLoadingLogs.value = false
        }
    }

    fun loadCalendar(baseUrl: String, syncGoogle: Boolean = false) {
        if (baseUrl.isBlank()) return
        currentBaseUrl = baseUrl.trim().trimEnd('/')
        viewModelScope.launch {
            _isLoadingCalendar.value = true
            api.getCalendarEvents(baseUrl = baseUrl, limit = 260, syncGoogle = syncGoogle)
                .onSuccess { items ->
                    allCalendarEvents = items
                    applyCalendarFilters()
                }
                .onFailure { e ->
                    _error.value = "Failed to load calendar: ${e.message}"
                }
            _isLoadingCalendar.value = false
        }
    }

    fun loadRoutines(baseUrl: String, detect: Boolean = true) {
        if (baseUrl.isBlank()) return
        currentBaseUrl = baseUrl.trim().trimEnd('/')
        viewModelScope.launch {
            _isLoadingRoutines.value = true
            api.getRoutines(baseUrl = baseUrl, limit = 260, detect = detect)
                .onSuccess { items ->
                    allRoutines = items.sortedWith(
                        compareByDescending<RoutineItem> { it.active }
                            .thenByDescending { it.confidence }
                            .thenByDescending { it.updatedAt }
                    )
                    applyRoutineFilters()
                }
                .onFailure { e ->
                    _error.value = "Failed to load routines: ${e.message}"
                }
            _isLoadingRoutines.value = false
        }
    }

    fun loadQuestions(baseUrl: String) {
        if (baseUrl.isBlank()) return
        currentBaseUrl = baseUrl.trim().trimEnd('/')
        viewModelScope.launch {
            _isLoadingQuestions.value = true
            api.getQuestions(baseUrl = baseUrl, limit = 20)
                .onSuccess { items ->
                    _qaItems.value = items
                }
                .onFailure { e ->
                    _error.value = "Failed to load questions: ${e.message}"
                }
            _isLoadingQuestions.value = false
        }
    }

    fun answerQuestion(baseUrl: String, item: DataQuestionItem, answer: String) {
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            // Optimistically remove the question from the list
            _qaItems.value = _qaItems.value.filter { it.id != item.id }

            val res = api.answerQuestion(baseUrl, item, answer)
            if (res.isSuccess) {
                redacted@example.invalid(baseUrl)
                redacted@example.invalid(baseUrl)
            } else {
                // If failed, reload to restore the question
                _error.value = "Failed to submit answer"
                redacted@example.invalid(baseUrl)
            }
        }
    }

    fun searchLogs(query: String) {
        currentLogQuery = query.trim().lowercase()
        applyLogFilters()
    }

    fun setLogTypeFilter(key: String) {
        _selectedLogType.value = key.trim().ifBlank { "highlights" }
        applyLogFilters()
    }

    fun searchCalendar(query: String) {
        currentCalendarQuery = query.trim().lowercase()
        applyCalendarFilters()
    }

    fun searchRoutines(query: String) {
        currentRoutineQuery = query.trim().lowercase()
        applyRoutineFilters()
    }

    fun upsertFact(id: Long?, text: String, category: String) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.upsertFact(base, id, text, category)
                .onSuccess {
                    loadFacts(base)
                }
                .onFailure { e ->
                    _error.value = "Fact save failed: ${e.message}"
                }
        }
    }

    fun deleteFact(id: Long) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.deleteFact(base, id)
                .onSuccess {
                    loadFacts(base)
                }
                .onFailure { e ->
                    _error.value = "Fact delete failed: ${e.message}"
                }
        }
    }

    fun upsertLog(id: Long?, kind: String, text: String, ts: String = "", deviceId: String = "") {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.upsertLog(base, id, kind, text, ts, deviceId)
                .onSuccess {
                    loadLogs(base)
                }
                .onFailure { e ->
                    _error.value = "Log save failed: ${e.message}"
                }
        }
    }

    fun deleteLog(id: Long) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.deleteLog(base, id)
                .onSuccess {
                    loadLogs(base)
                }
                .onFailure { e ->
                    _error.value = "Log delete failed: ${e.message}"
                }
        }
    }

    fun upsertTranscript(
        id: Long,
        text: String,
        sourceType: String,
        speakerId: String,
        speakerConfidence: Double,
        tags: String,
        importance: Double,
        mediaLikelihood: Double,
        dialogDensity: Double
    ) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.upsertTranscript(
                baseUrl = base,
                id = id,
                text = text,
                sourceType = sourceType,
                speakerId = speakerId,
                speakerConfidence = speakerConfidence,
                tags = tags,
                importance = importance,
                mediaLikelihood = mediaLikelihood,
                dialogDensity = dialogDensity
            )
                .onSuccess {
                    loadLogs(base)
                }
                .onFailure { e ->
                    _error.value = "Transcript save failed: ${e.message}"
                }
        }
    }

    fun upsertCalendarEvent(
        id: Long?,
        title: String,
        startTs: String,
        endTs: String,
        timezone: String = "",
        recurrenceRule: String = "",
        location: String = "",
        notes: String = "",
        isAllDay: Boolean = false,
        source: String = "local",
        status: String = "confirmed",
        syncGoogle: Boolean = false
    ) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.upsertCalendarEvent(
                baseUrl = base,
                event = CalendarSyncEvent(
                    source = source,
                    title = title,
                    startTs = startTs,
                    endTs = endTs,
                    timezone = timezone,
                    recurrenceRule = recurrenceRule,
                    location = location,
                    notes = notes,
                    isAllDay = isAllDay,
                    status = status,
                    syncGoogle = syncGoogle
                ),
                id = id
            )
                .onSuccess {
                    loadCalendar(base)
                }
                .onFailure { e ->
                    _error.value = "Calendar save failed: ${e.message}"
                }
        }
    }

    fun deleteCalendarEvent(id: Long, syncGoogle: Boolean = false) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.deleteCalendarEvent(base, id, syncGoogle)
                .onSuccess {
                    loadCalendar(base)
                }
                .onFailure { e ->
                    _error.value = "Calendar delete failed: ${e.message}"
                }
        }
    }

    fun upsertRoutine(
        id: Long?,
        title: String,
        kind: String = "custom",
        anchorKey: String = "",
        hourBucket: Int = -1,
        weekdays: String = "",
        note: String = "",
        confidence: Double = 0.0,
        active: Boolean = true,
        source: String = "manual",
        occurrences: Int = 0,
        firstSeenTs: String = "",
        lastSeenTs: String = ""
    ) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.upsertRoutine(
                baseUrl = base,
                routine = RoutineSyncEvent(
                    title = title,
                    kind = kind,
                    anchorKey = anchorKey,
                    hourBucket = hourBucket,
                    weekdays = weekdays,
                    note = note,
                    confidence = confidence,
                    source = source,
                    active = active,
                    occurrences = occurrences,
                    firstSeenTs = firstSeenTs,
                    lastSeenTs = lastSeenTs
                ),
                id = id
            )
                .onSuccess {
                    loadRoutines(base, detect = false)
                }
                .onFailure { e ->
                    _error.value = "Routine save failed: ${e.message}"
                }
        }
    }

    fun deleteRoutine(id: Long) {
        val base = currentBaseUrl
        if (base.isBlank()) {
            _error.value = "Missing server URL"
            return
        }
        viewModelScope.launch {
            api.deleteRoutine(base, id)
                .onSuccess {
                    loadRoutines(base, detect = false)
                }
                .onFailure { e ->
                    _error.value = "Routine delete failed: ${e.message}"
                }
        }
    }

    fun clearError() {
        _error.value = ""
    }

    private fun compressStateChanges(items: List<PhoneLogEvent>): List<PhoneLogEvent> {
        if (items.isEmpty()) return items
        val lastByKind = mutableMapOf<String, String>()
        val out = ArrayList<PhoneLogEvent>(items.size)
        for (row in items) {
            val kind = row.kind.trim().lowercase()
            if (kind !in stateKinds) {
                out.add(row)
                continue
            }
            val normalized = row.text.trim()
            val prev = lastByKind[kind]
            if (prev == null || prev != normalized) {
                out.add(row)
                lastByKind[kind] = normalized
            }
        }
        return out
    }

    private fun applyLogFilters() {
        var rows = allLogs
        val selected = _selectedLogType.value
        rows = when (selected) {
            "all" -> rows
            "highlights" -> rows.filter { normalizeKind(it.kind) !in lowSignalKinds }
            else -> rows.filter { normalizeKind(it.kind) == selected }
        }

        val q = currentLogQuery
        if (q.isNotEmpty()) {
            rows = rows.filter { row ->
                row.kind.lowercase().contains(q) ||
                    row.text.lowercase().contains(q) ||
                    row.ts.lowercase().contains(q) ||
                    row.deviceId.lowercase().contains(q)
            }
        }
        _logs.value = rows
    }

    private fun applyCalendarFilters() {
        var rows = allCalendarEvents
        val q = currentCalendarQuery
        if (q.isNotEmpty()) {
            rows = rows.filter { row ->
                row.title.lowercase().contains(q) ||
                    row.display.lowercase().contains(q) ||
                    row.location.lowercase().contains(q) ||
                    row.notes.lowercase().contains(q) ||
                    row.source.lowercase().contains(q) ||
                    row.startTs.lowercase().contains(q) ||
                    row.endTs.lowercase().contains(q)
            }
        }
        _calendarEvents.value = rows
    }

    private fun applyRoutineFilters() {
        var rows = allRoutines
        val q = currentRoutineQuery
        if (q.isNotEmpty()) {
            rows = rows.filter { row ->
                row.title.lowercase().contains(q) ||
                    row.display.lowercase().contains(q) ||
                    row.kind.lowercase().contains(q) ||
                    row.anchorKey.lowercase().contains(q) ||
                    row.weekdays.lowercase().contains(q) ||
                    row.note.lowercase().contains(q) ||
                    row.source.lowercase().contains(q) ||
                    row.updatedAt.lowercase().contains(q)
            }
        }
        _routines.value = rows
    }

    private fun rebuildLogTypeOptions() {
        if (allLogs.isEmpty()) {
            _logTypeOptions.value = listOf(
                LogTypeOption("highlights", "Highlights", 0),
                LogTypeOption("all", "All", 0)
            )
            _selectedLogType.value = "highlights"
            return
        }
        val counts = linkedMapOf<String, Int>()
        for (row in allLogs) {
            val k = normalizeKind(row.kind)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val highlightCount = allLogs.count { normalizeKind(it.kind) !in lowSignalKinds }
        val options = ArrayList<LogTypeOption>()
        options.add(LogTypeOption("highlights", "Highlights", highlightCount))
        options.add(LogTypeOption("all", "All", allLogs.size))

        val sortedKinds = counts.keys.sortedWith(
            compareBy<String> { idxOfKind(it) }.thenBy { it }
        )
        for (k in sortedKinds) {
            options.add(LogTypeOption(k, prettyKindLabel(k), counts[k] ?: 0))
        }
        _logTypeOptions.value = options

        val selected = _selectedLogType.value
        if (options.none { it.key == selected }) {
            _selectedLogType.value = "highlights"
        }
    }

    private fun refreshSpeakerOptionsFromServer(baseUrl: String) {
        viewModelScope.launch {
            api.getSpeakerProfiles(baseUrl)
                .onSuccess { payload ->
                    val fromServer = (payload.profiles.map { it.name } + payload.knownLabels.map { it.name })
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    serverSpeakerNames = fromServer
                    rebuildSpeakerOptions()
                }
                .onFailure {
                }
        }
    }

    private fun rebuildSpeakerOptions() {
        val fromLogs = allLogs
            .asSequence()
            .map { it.speakerId.trim() }
            .filter { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            .toList()
        val merged = (serverSpeakerNames + fromLogs)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        _speakerOptions.value = merged
    }

    private fun normalizeKind(kind: String): String = kind.trim().lowercase().ifBlank { "event" }

    private fun idxOfKind(kind: String): Int {
        val idx = logKindPriority.indexOf(kind)
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    private fun prettyKindLabel(kind: String): String {
        val raw = kind.replace('_', ' ').replace('-', ' ').trim()
        if (raw.isEmpty()) return "Event"
        return raw.split(" ").joinToString(" ") { part ->
            if (part.isEmpty()) part else part.replaceFirstChar { it.uppercase() }
        }
    }

    private fun parseTimestamp(value: String): Long {
        val raw = value.trim()
        if (raw.isEmpty()) return System.currentTimeMillis()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = fmt.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }
}
