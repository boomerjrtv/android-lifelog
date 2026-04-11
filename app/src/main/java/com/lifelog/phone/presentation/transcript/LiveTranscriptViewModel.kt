package com.lifelog.phone.presentation.transcript

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.local.PhoneLogDao
import com.lifelog.phone.data.local.PhoneLogEntity
import com.lifelog.phone.data.local.SpeakerProfileDao
import com.lifelog.phone.data.local.SpeakerProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class LiveTranscriptItem(
    val id: Long,
    val transcriptId: Long,
    val ts: String,
    val text: String,
    val speakerId: String,
    val speakerClusterId: String,
    val sourceType: String,
)

@HiltViewModel
class LiveTranscriptViewModel @Inject constructor(
    private val phoneLogDao: PhoneLogDao,
    private val speakerProfileDao: SpeakerProfileDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val slotRegex = Regex("S[0-9]+")

    private val _items = MutableStateFlow<List<LiveTranscriptItem>>(emptyList())
    val items: StateFlow<List<LiveTranscriptItem>> = _items.asStateFlow()

    private val _activeSpeakerSlot = MutableStateFlow(settingsRepository.cachedActiveSpeakerSlot)
    val activeSpeakerSlot: StateFlow<String> = _activeSpeakerSlot.asStateFlow()

    private val _speakerOptions = MutableStateFlow<List<String>>(emptyList())
    val speakerOptions: StateFlow<List<String>> = _speakerOptions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastUpdated = MutableStateFlow("")
    val lastUpdated: StateFlow<String> = _lastUpdated.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _livePartial = MutableStateFlow("")
    val livePartial: StateFlow<String> = _livePartial.asStateFlow()

    private val _isRecognizerActive = MutableStateFlow(false)
    val isRecognizerActive: StateFlow<Boolean> = _isRecognizerActive.asStateFlow()

    private var refreshJob: Job? = null
    private var observeJob: Job? = null

    private val localTsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun clusterIdForSlot(slot: String): String = "slot_${slot.lowercase()}"

    fun startLiveRefresh() {
        if (observeJob?.isActive != true) {
            observeJob = viewModelScope.launch {
            _isLoading.value = true
                phoneLogDao.observeByKind("transcript", 150).collectLatest { rows ->
                    applyRows(rows)
                    _isLoading.value = false
                }
            }
        }
        if (refreshJob?.isActive != true) {
            refreshJob = viewModelScope.launch {
                while (true) {
                    refreshOnce()
                    kotlinx.coroutines.delay(2000L)
                }
            }
        }
        viewModelScope.launch {
            loadSpeakerOptions()
        }
    }

    fun stopLiveRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        observeJob?.cancel()
        observeJob = null
    }

    fun setRecognizerActive(active: Boolean) {
        _isRecognizerActive.value = active
        _status.value = when {
            active -> "Listening live"
            _status.value.startsWith("Error") -> _status.value
            else -> "Paused"
        }
    }

    fun setRecognizerStatus(status: String) {
        _status.value = status.trim()
    }

    fun setLivePartial(text: String) {
        _livePartial.value = text.trim()
    }

    fun commitTranscript(text: String) {
        val clean = text.trim()
        if (clean.length < 2) return
        viewModelScope.launch {
            runCatching {
                val ts = localTsFormatter.format(LocalDateTime.now())
                phoneLogDao.insert(
                    PhoneLogEntity(
                        id = System.currentTimeMillis(),
                        ts = ts,
                        deviceId = Build.MODEL,
                        kind = "transcript",
                        text = clean,
                        transcriptId = 0L,
                        sourceType = "android_speech_recognizer",
                        speakerId = "",
                        speakerConfidence = 0.0,
                        tags = "",
                        importance = 0.7,
                        mediaLikelihood = 0.0,
                        dialogDensity = 0.0,
                        source = "live_transcript",
                        speakerClusterId = "",
                    )
                )
            }.onSuccess {
                _livePartial.value = ""
                refreshOnce()
                loadSpeakerOptions()
                _status.value = "Live transcript updated"
            }.onFailure { e ->
                _status.value = "Error saving transcript: ${e.message ?: "unknown"}"
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            refreshOnce()
        }
    }

    fun setActiveSpeakerSlot(slot: String) {
        val clean = slot.trim().uppercase()
        val normalized = if (clean.matches(slotRegex)) clean else ""
        settingsRepository.activeSpeakerSlot = normalized
        _activeSpeakerSlot.value = normalized
        _status.value = if (normalized.isBlank()) {
            "Speaker slot tracking off"
        } else {
            "Active speaker slot: $normalized"
        }
    }

    private suspend fun refreshOnce() {
        _isLoading.value = true
        runCatching {
            phoneLogDao.getByKind("transcript", 150)
        }.onSuccess { rows ->
            applyRows(rows)
        }.onFailure { e ->
            _status.value = "Error loading transcripts: ${e.message ?: "unknown"}"
        }
        _isLoading.value = false
    }

    private suspend fun applyRows(rows: List<PhoneLogEntity>) {
        val profileMap = speakerProfileDao.getAll().associateBy { it.clusterId }
        val mapped = rows.map {
            val clusterId = it.speakerClusterId.trim()
            val profileName = profileMap[clusterId]?.displayName.orEmpty().trim()
            val displaySpeaker = it.speakerId.trim().ifBlank { profileName }
            LiveTranscriptItem(
                id = it.id,
                transcriptId = it.transcriptId,
                ts = it.ts,
                text = it.text,
                speakerId = displaySpeaker,
                speakerClusterId = clusterId,
                sourceType = it.sourceType,
            )
        }
        _items.value = mapped
        _speakerOptions.value = mapped
            .map { it.speakerId.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
        _lastUpdated.value = java.time.LocalTime.now().withNano(0).toString()
        if (_status.value.startsWith("Error")) _status.value = ""
    }

    private suspend fun loadSpeakerOptions() {
        val names = (phoneLogDao.getByKind("transcript", 300)
            .map { it.speakerId.trim() } + speakerProfileDao.getAll().map { it.displayName.trim() })
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
        _speakerOptions.value = names
    }

    private fun parseTsMillis(raw: String): Long {
        val clean = raw.trim()
        if (clean.isEmpty()) return 0L
        clean.toLongOrNull()?.let { return it }
        return try {
            java.time.Instant.parse(clean).toEpochMilli()
        } catch (_: Exception) {
            try {
                // Try format with 'T' but no 'Z'
                LocalDateTime.parse(clean.substringBefore("+").substringBefore("Z"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(clean, localTsFormatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (_: DateTimeParseException) {
                    0L
                }
            }
        }
    }

    fun assignSpeaker(item: LiveTranscriptItem, speaker: String) {
        val clean = speaker.trim().ifBlank { return }
        val oldSpeaker = item.speakerId.trim().uppercase()
        val isOldSlot = oldSpeaker.matches(slotRegex)
        val isNewSlot = clean.uppercase().matches(slotRegex)
        viewModelScope.launch {
            if (isOldSlot && !isNewSlot) {
                runCatching {
                    phoneLogDao.relabelSpeaker(oldSpeaker, clean, 0.92)
                }.onFailure { e ->
                    _status.value = "Error relabeling speaker group: ${e.message ?: "unknown"}"
                    return@launch
                }
                val fromCluster = clusterIdForSlot(oldSpeaker.lowercase())
                runCatching {
                    speakerProfileDao.upsert(
                        SpeakerProfileEntity(
                            clusterId = fromCluster,
                            displayName = clean,
                            sampleCount = 1,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    )
                }
                _items.value = _items.value.map { row ->
                    if (row.speakerId.trim().uppercase() == oldSpeaker) row.copy(speakerId = clean) else row
                }
                _status.value = "Relabeled $oldSpeaker to $clean"
                return@launch
            }

            val clusterId = when {
                item.speakerClusterId.trim().isNotEmpty() -> item.speakerClusterId.trim()
                isNewSlot -> clusterIdForSlot(clean.uppercase())
                else -> "cluster_${item.id}"
            }

            val targetTs = parseTsMillis(item.ts)
            runCatching {
                phoneLogDao.updateSpeakerAndClusterById(item.id, clean, 1.0, clusterId)
            }.onFailure { e ->
                _status.value = "Error saving local speaker: ${e.message ?: "unknown"}"
                return@launch
            }

            if (!isNewSlot) {
                runCatching {
                    speakerProfileDao.upsert(
                        SpeakerProfileEntity(
                            clusterId = clusterId,
                            displayName = clean,
                            sampleCount = 1,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    )
                }
                runCatching { phoneLogDao.updateSpeakerByClusterId(clusterId, clean, 0.9) }
            }

            var nearbyUpdated = 0
            val adjacentUnlabeledIds = mutableSetOf<Long>()
            val idx = _items.value.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                val start = (idx - 4).coerceAtLeast(0)
                val end = (idx + 4).coerceAtMost(_items.value.lastIndex)
                for (i in start..end) {
                    if (i == idx) continue
                    val row = _items.value[i]
                    if (row.speakerId.trim().isEmpty()) adjacentUnlabeledIds += row.id
                }
            }
            if (targetTs > 0L) {
                val nearby = phoneLogDao.getByKind("transcript", 200)
                    .filter {
                        it.id != item.id &&
                            it.speakerId.trim().isEmpty() &&
                            kotlin.math.abs(parseTsMillis(it.ts) - targetTs) <= 180_000L
                    }
                    .take(20)
                nearby.forEach { row ->
                    runCatching {
                        phoneLogDao.updateSpeakerAndClusterById(row.id, clean, 0.72, clusterId)
                    }
                    nearbyUpdated += 1
                    adjacentUnlabeledIds.remove(row.id)
                }
            }

            adjacentUnlabeledIds.take(12).forEach { id ->
                runCatching { phoneLogDao.updateSpeakerAndClusterById(id, clean, 0.68, clusterId) }
                nearbyUpdated += 1
            }

            _items.value = _items.value.map { row ->
                when {
                    row.id == item.id -> row.copy(speakerId = clean, speakerClusterId = clusterId)
                    adjacentUnlabeledIds.contains(row.id) -> row.copy(speakerId = clean, speakerClusterId = clusterId)
                    targetTs > 0L && row.speakerId.isBlank() && kotlin.math.abs(parseTsMillis(row.ts) - targetTs) <= 180_000L -> row.copy(speakerId = clean, speakerClusterId = clusterId)
                    else -> row
                }
            }

            _status.value = if (nearbyUpdated > 0) {
                "Speaker saved (+$nearbyUpdated nearby lines)"
            } else {
                "Speaker saved"
            }
        }
    }

    fun mergeSpeakerLabels(fromSpeaker: String, toSpeaker: String) {
        val from = fromSpeaker.trim()
        val to = toSpeaker.trim()
        if (from.isEmpty() || to.isEmpty()) {
            _status.value = "Both source and target speaker labels are required"
            return
        }
        if (from.equals(to, ignoreCase = true)) {
            _status.value = "Source and target labels are the same"
            return
        }
        viewModelScope.launch {
            runCatching {
                phoneLogDao.relabelSpeaker(from, to, 0.9)
            }.onFailure { e ->
                _status.value = "Merge failed: ${e.message ?: "unknown"}"
                return@launch
            }

            runCatching {
                speakerProfileDao.getAll()
                    .filter { it.displayName.equals(from, ignoreCase = true) }
                    .forEach {
                        speakerProfileDao.upsert(
                            it.copy(
                                displayName = to,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        )
                    }
            }

            val before = _items.value.count { it.speakerId.trim().equals(from, ignoreCase = true) }
            _items.value = _items.value.map { row ->
                if (row.speakerId.trim().equals(from, ignoreCase = true)) row.copy(speakerId = to) else row
            }
            _speakerOptions.value = _speakerOptions.value
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it.equals(from, ignoreCase = true)) to else it }
                .distinct()
                .sortedBy { it.lowercase() }

            _status.value = "Merged $from into $to ($before rows in current view)"
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveRefresh()
    }
}
