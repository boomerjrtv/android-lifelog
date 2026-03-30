package com.lifelog.phone.presentation.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.phone.data.Message
import com.lifelog.phone.data.MessageRepository
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.ChatResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.text.ParseException
import javax.inject.Inject

data class ConversationMemoryBar(
    val lastTopic: String = "",
    val lastQuestion: String = "",
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val TAG = "ChatViewModel"
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    private val _messageEvidence = MutableStateFlow<Map<Long, ChatResult>>(emptyMap())
    val messageEvidence: StateFlow<Map<Long, ChatResult>> = _messageEvidence.asStateFlow()
    private val _conversationMemory = MutableStateFlow(ConversationMemoryBar())
    val conversationMemory: StateFlow<ConversationMemoryBar> = _conversationMemory.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    // Audio playback state
    private val _audioPosition = MutableStateFlow(0L)
    val audioPosition: StateFlow<Long> = _audioPosition.asStateFlow()

    private val _audioDuration = MutableStateFlow(0L)
    val audioDuration: StateFlow<Long> = _audioDuration.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private val _currentAudioFile = MutableStateFlow<java.io.File?>(null)
    val currentAudioFile: StateFlow<java.io.File?> = _currentAudioFile.asStateFlow()

    private var currentBaseUrl: String = ""
    private var activeSessionId: String = newSessionId()
    private var mediaPlayer: MediaPlayer? = null
    private var audioUpdateJob: kotlinx.coroutines.Job? = null

    init {
        runInitialLoad()
        startConversationSync()
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun runInitialLoad() {
        viewModelScope.launch {
            messageRepository.cleanupDuplicateMessages()
            setMessages(messageRepository.getAll())
        }
    }

    fun playAudio(stem: String, timestamp: String = "") {
        val baseUrl = settingsRepository.cachedBaseUrl.ifBlank { settingsRepository.baseUrl }
        val token = settingsRepository.cachedToken.ifBlank { settingsRepository.token }
        if (baseUrl.isBlank() || stem.isBlank()) {
            Log.e(TAG, "playAudio: missing baseUrl=$baseUrl or stem=$stem")
            _errorMessage.value = "Missing server URL"
            return
        }

        val encodedStem = Uri.encode(stem)
        val url = if (baseUrl.endsWith("/")) {
            "${baseUrl}audio/$encodedStem"
        } else {
            "$baseUrl/audio/$encodedStem"
        }

        Log.i(TAG, "playAudio: baseUrl=$baseUrl, stem=$stem")
        Log.i(TAG, "playAudio: FULL TOKEN=${token}")
        Log.i(TAG, "playAudio: url=$url")
        Log.i(TAG, "playAudio: baseUrl empty=${baseUrl.isEmpty()}, token empty=${token.isEmpty()}, token length=${token.length}")

        val offsetMs = calculateOffset(stem, timestamp)

        viewModelScope.launch {
            Log.i(TAG, "playAudio: coroutine launched")
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    Log.i(TAG, "playAudio: phase 1 - creating client on IO thread")
                    // Download audio file first using OkHttp (which includes auth headers)
                    val client = okhttp3.OkHttpClient()

                    Log.i(TAG, "playAudio: Building request with:")
                    Log.i(TAG, "  url=$url")
                    Log.i(TAG, "  Authorization=Bearer ${token.take(10)}...${token.takeLast(10)}")
                    Log.i(TAG, "  X-Phone-Token=${token.take(10)}...${token.takeLast(10)}")

                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("X-Phone-Token", token)
                        .build()

                    Log.i(TAG, "playAudio: phase 2 - executing request for url=$url")
                    Log.i(TAG, "playAudio: Request headers: ${req.headers}")
                    val response = client.newCall(req).execute()
                    Log.i(TAG, "playAudio: phase 3 - got response code=${response.code}, message=${response.message}")

                    if (!response.isSuccessful) {
                        Log.e(TAG, "playAudio: HTTP error ${response.code}")
                        _errorMessage.value = "Server error: ${response.code}"
                        return@withContext null
                    }

                    Log.i(TAG, "playAudio: phase 4 - extracting audio data")
                    val audioData = response.body?.bytes()
                    if (audioData == null || audioData.isEmpty()) {
                        Log.e(TAG, "playAudio: empty response")
                        _errorMessage.value = "No audio data received"
                        return@withContext null
                    }

                    Log.i(TAG, "playAudio: phase 5 - got ${audioData.size} bytes, saving to file")
                    // Save to temp file
                    val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
                    file.writeBytes(audioData)
                    Log.i(TAG, "playAudio: phase 6 - saved to ${file.absolutePath}")
                    file
                }

                if (tempFile == null) return@launch

                // Stop any existing playback
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) it.stop()
                    } catch (_: Exception) {}
                    it.release()
                }
                audioUpdateJob?.cancel()

                // Store current file for saving
                _currentAudioFile.value = tempFile
                _audioPosition.value = 0
                _audioDuration.value = 0
                _isAudioPlaying.value = false

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
                        Log.i(TAG, "MediaPlayer: prepared, duration=${it.duration}ms, offsetMs=$offsetMs ms")
                        _audioDuration.value = it.duration.toLong()
                        if (offsetMs > 0) {
                            it.seekTo(offsetMs.toInt())
                        }
                        it.start()
                        _isAudioPlaying.value = true
                        startAudioPositionUpdates()
                    }
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer == it) mediaPlayer = null
                        _isAudioPlaying.value = false
                        audioUpdateJob?.cancel()
                        try { tempFile.delete() } catch (_: Exception) {}
                    }
                    setOnErrorListener { innerMp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        _errorMessage.value = "Playback error: $what/$extra"
                        _isAudioPlaying.value = false
                        audioUpdateJob?.cancel()
                        innerMp.release()
                        if (mediaPlayer == innerMp) mediaPlayer = null
                        try { tempFile.delete() } catch (_: Exception) {}
                        true
                    }
                }
                mediaPlayer = mp
            } catch (e: Exception) {
                Log.e(TAG, "playAudio exception: ${e.javaClass.simpleName}", e)
                _errorMessage.value = "Error: ${e.javaClass.simpleName} - ${e.message ?: e.cause?.message ?: "Unknown error"}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = ""
    }

    fun seekAudio(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
    }

    fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isAudioPlaying.value = false
                audioUpdateJob?.cancel()
            }
        }
    }

    fun resumeAudio() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                _isAudioPlaying.value = true
                startAudioPositionUpdates()
            }
        }
    }

    fun saveAudioClip(fileName: String = "lifelog_clip_${System.currentTimeMillis()}.wav") {
        _currentAudioFile.value?.let { file ->
            try {
                val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                )
                val destFile = java.io.File(documentsDir, fileName)
                file.copyTo(destFile, overwrite = true)
                _errorMessage.value = "Saved to: ${destFile.absolutePath}"
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    private fun startAudioPositionUpdates() {
        audioUpdateJob = viewModelScope.launch {
            while (isActive && mediaPlayer != null) {
                try {
                    val mp = mediaPlayer
                    if (mp != null && mp.isPlaying) {
                        _audioPosition.value = mp.currentPosition.toLong()
                        _audioDuration.value = mp.duration.toLong()
                    }
                    delay(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating audio position", e)
                }
            }
        }
    }

    fun playTTS(text: String) {
        val baseUrl = settingsRepository.cachedBaseUrl.ifBlank { settingsRepository.baseUrl }
        val token = settingsRepository.cachedToken.ifBlank { settingsRepository.token }
        if (baseUrl.isBlank() || text.isBlank()) {
            _errorMessage.value = "Missing server URL or text"
            Log.e(TAG, "playTTS: baseUrl=$baseUrl, token=${token.take(10)}...")
            return
        }

        val encodedText = Uri.encode(text)
        val url = if (baseUrl.endsWith("/")) {
            "${baseUrl}tts?text=$encodedText"
        } else {
            "$baseUrl/tts?text=$encodedText"
        }

        Log.i(TAG, "playTTS: baseUrl=$baseUrl, token=${token.take(10)}..., url=$url")

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

                    Log.i(TAG, "playTTS: downloading from $url with auth headers")
                    val response = client.newCall(req).execute()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "playTTS: HTTP error ${response.code}")
                        _errorMessage.value = "Server error: ${response.code}"
                        return@withContext null
                    }

                    val audioData = response.body?.bytes()
                    if (audioData == null || audioData.isEmpty()) {
                        Log.e(TAG, "playTTS: empty response")
                        _errorMessage.value = "No audio data received"
                        return@withContext null
                    }

                    // Save to temp file
                    val file = java.io.File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                    file.writeBytes(audioData)
                    file
                }

                if (tempFile == null) return@launch

                // Stop any existing playback
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) it.stop()
                    } catch (_: Exception) {}
                    it.release()
                }
                audioUpdateJob?.cancel()

                // Store current file and reset state
                _currentAudioFile.value = tempFile
                _audioPosition.value = 0
                _audioDuration.value = 0
                _isAudioPlaying.value = false

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
                        Log.i(TAG, "MediaPlayer TTS: prepared, duration=${it.duration}ms")
                        _audioDuration.value = it.duration.toLong()
                        // Speed up TTS playback to 1.5x
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                it.playbackParams = android.media.PlaybackParams().setSpeed(1.5f)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set playback speed", e)
                        }
                        it.start()
                        _isAudioPlaying.value = true
                        startAudioPositionUpdates()
                    }
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer == it) mediaPlayer = null
                        _isAudioPlaying.value = false
                        audioUpdateJob?.cancel()
                        try { tempFile.delete() } catch (_: Exception) {}
                    }
                    setOnErrorListener { innerMp, what, extra ->
                        Log.e(TAG, "MediaPlayer TTS error: what=$what extra=$extra")
                        _errorMessage.value = "TTS error: $what/$extra"
                        _isAudioPlaying.value = false
                        audioUpdateJob?.cancel()
                        innerMp.release()
                        if (mediaPlayer == innerMp) mediaPlayer = null
                        try { tempFile.delete() } catch (_: Exception) {}
                        true
                    }
                }
                mediaPlayer = mp
            } catch (e: Exception) {
                Log.e(TAG, "playTTS exception: ${e.javaClass.simpleName}", e)
                _errorMessage.value = "Error: ${e.javaClass.simpleName} - ${e.message ?: e.cause?.message ?: "Unknown error"}"
            }
        }
    }

    private fun calculateOffset(stem: String, timestamp: String): Long {
        if (timestamp.isBlank() || !stem.startsWith("SPRINT_")) return 0L
        
        try {
            // SPRINT_YYYYMMDD_HHMMSS_...
            val parts = stem.split("_")
            if (parts.size < 3) return 0L
            val stemTimeStr = "${parts[1]}_${parts[2]}"
            val stemFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val stemDate = stemFmt.parse(stemTimeStr) ?: return 0L
            
            // Timestamp: YYYY-MM-DD HH:MM:SS
            val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val tsDate = tsFmt.parse(timestamp.take(16)) ?: return 0L
            
            val diffMs = tsDate.time - stemDate.time
            return if (diffMs > 0) diffMs else 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating offset", e)
            return 0L
        }
    }

    private fun startConversationSync() {
        viewModelScope.launch {
            while (isActive) {
                if (currentBaseUrl.isNotEmpty()) {
                    val synced = messageRepository.syncVoiceConversations(currentBaseUrl)
                    if (synced > 0) {
                        loadMessages()
                    }
                }
                delay(10_000)
            }
        }
    }

    fun setBaseUrl(baseUrl: String) {
        currentBaseUrl = baseUrl
    }

    fun loadMessages() {
        viewModelScope.launch {
            setMessages(messageRepository.getAll())
        }
    }

    fun sendMessage(
        baseUrl: String,
        text: String,
        voice: Boolean = false,
        onAssistantReply: ((String) -> Unit)? = null
    ) {
        currentBaseUrl = baseUrl
        viewModelScope.launch {
            _isLoading.value = true

            val userMsg = Message(role = "user", text = text)
            val savedUser = messageRepository.insert(userMsg)
            _messages.value = _messages.value + savedUser

            val mealCaptured = messageRepository.capturePendingMealFromChat(baseUrl, text)
            if (mealCaptured) {
                val assistantMsg = Message(role = "assistant", text = "Saved. I logged that meal.")
                val savedAssistant = messageRepository.insert(assistantMsg)
                _messages.value = _messages.value.toMutableList().apply { add(savedAssistant) }
                onAssistantReply?.invoke(assistantMsg.text)
                _isLoading.value = false
                return@launch
            }

            messageRepository.sendChat(baseUrl, text, sessionId = activeSessionId, voice = voice)
                .onSuccess { result ->
                    val reply = result.reply.trim()
                    if (reply.isNotEmpty()) {
                        val assistantMsg = Message(
                            role = "assistant", 
                            text = reply, 
                            stem = result.replyStem
                        )
                        val savedAssistant = messageRepository.insert(assistantMsg)
                        _messages.value = _messages.value + savedAssistant
                        if (result.mode.isNotBlank() || result.evidence.isNotEmpty() || result.reason.isNotBlank()) {
                            addMessageEvidence(savedAssistant.id, result)
                        } else {
                            recomputeConversationMemory()
                        }
                    }
                    onAssistantReply?.invoke(reply)
                }
                .onFailure { error ->
                    val msg = "Error: ${error.message}"
                    val errorMsg = Message(role = "assistant", text = msg)
                    val savedError = messageRepository.insert(errorMsg)
                    _messages.value = _messages.value + savedError
                    onAssistantReply?.invoke(msg)
                }

            _isLoading.value = false
        }
    }

    fun sendVoiceMessage(baseUrl: String, audioData: ByteArray) {
        currentBaseUrl = baseUrl
        viewModelScope.launch {
            _isLoading.value = true

            val userMsgPlaceholder = Message(role = "user", text = "🎤 Voice message")
            val savedUser = messageRepository.insert(userMsgPlaceholder)
            _messages.value = _messages.value + savedUser

            messageRepository.sendVoice(baseUrl, audioData, sessionId = activeSessionId)
                .onSuccess { (result, stem) ->
                    val reply = result.reply.trim()
                    if (reply.isNotEmpty()) {
                        val assistantMsg = Message(role = "assistant", text = reply, stem = stem)
                        val savedAssistant = messageRepository.insert(assistantMsg)
                        _messages.value = _messages.value + savedAssistant
                        if (result.mode.isNotBlank() || result.evidence.isNotEmpty() || result.reason.isNotBlank()) {
                            addMessageEvidence(savedAssistant.id, result)
                        } else {
                            recomputeConversationMemory()
                        }
                    }
                }
                .onFailure { error ->
                    val errorMsg = Message(role = "assistant", text = "Error: ${error.message}")
                    val savedError = messageRepository.insert(errorMsg)
                    _messages.value = _messages.value + savedError
                }

            _isLoading.value = false
        }
    }

    fun startRecording() {
        _isRecording.value = true
    }

    fun stopRecording() {
        _isRecording.value = false
    }

    fun clearChat() {
        viewModelScope.launch {
            messageRepository.clear()
            _messages.value = emptyList()
            _messageEvidence.value = emptyMap()
            recomputeConversationMemory()
            activeSessionId = newSessionId()
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
            val marker = Message(role = "assistant", text = "---- New conversation ($stamp) ----")
            val savedMarker = messageRepository.insert(marker)
            _messages.value = _messages.value + savedMarker
            activeSessionId = newSessionId()
        }
    }

    fun seedAssistantPrompt(text: String, dedupeWindowMs: Long = 180_000L) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val inserted = messageRepository.insertIfNotRecent(
                role = "assistant",
                text = clean,
                windowMs = dedupeWindowMs
            )
            if (inserted != null) {
                setMessages(messageRepository.getAll())
            }
        }
    }

    private fun newSessionId(): String =
        "app_${UUID.randomUUID().toString().replace("-", "")}"

    fun applyEdit(
        baseUrl: String,
        target: Message,
        wrong: String,
        correct: String,
        editedText: String,
        replaceEverywhere: Boolean,
        onDone: (String) -> Unit
    ) {
        val wrongNorm = wrong.trim()
        val correctNorm = correct.trim()
        val editedNorm = editedText.trim()

        viewModelScope.launch {
            if (replaceEverywhere) {
                if (wrongNorm.isBlank() || correctNorm.isBlank()) {
                    onDone("Select text to replace, then enter replacement.")
                    return@launch
                }
                if (wrongNorm.equals(correctNorm, ignoreCase = true)) {
                    onDone("Original and replacement cannot be the same.")
                    return@launch
                }
                _isLoading.value = true
                    val result = messageRepository.replacePhraseEverywhere(baseUrl, wrongNorm, correctNorm)
                    result.onSuccess { out ->
                        messageRepository.replacePhraseAcrossLocalMessages(wrongNorm, correctNorm)
                        setMessages(messageRepository.getAll())
                        _messageEvidence.value = emptyMap()
                        recomputeConversationMemory()
                        onDone("Replaced ${out.totalUpdated} items across logs.")
                    }.onFailure { error ->
                        onDone("Replace failed: ${error.message ?: "unknown error"}")
                }
                _isLoading.value = false
                return@launch
            }

            if (target.id <= 0L) {
                onDone("Message is not ready for editing yet.")
                return@launch
            }

            if (wrongNorm.isNotBlank() && correctNorm.isNotBlank()) {
                if (wrongNorm.equals(correctNorm, ignoreCase = true)) {
                    onDone("Original and replacement cannot be the same.")
                    return@launch
                }
                val changed = messageRepository.replacePhraseInMessage(target.id, wrongNorm, correctNorm)
                if (!changed) {
                    onDone("No matching text found in this response.")
                    return@launch
                }
                setMessages(messageRepository.getAll())
                removeMessageEvidence(target.id)
                onDone("Updated this response.")
                return@launch
            }

            if (editedNorm.isNotBlank() && editedNorm != target.text) {
                val changed = messageRepository.updateMessageText(target.id, editedNorm)
                if (!changed) {
                    onDone("Could not update this response.")
                    return@launch
                }
                setMessages(messageRepository.getAll())
                removeMessageEvidence(target.id)
                onDone("Saved edited response.")
                return@launch
            }

            onDone("Enter the wrong text and replacement.")
        }
    }

    private fun setMessages(rows: List<Message>) {
        _messages.value = rows
        val ids = rows.map { it.id }.toSet()
        _messageEvidence.update { cur -> cur.filterKeys { it in ids } }
        recomputeConversationMemory()
    }

    private fun addMessageEvidence(messageId: Long, details: ChatResult) {
        _messageEvidence.update { cur -> cur + (messageId to details) }
        recomputeConversationMemory()
    }

    private fun removeMessageEvidence(messageId: Long) {
        _messageEvidence.update { cur -> cur - messageId }
        recomputeConversationMemory()
    }

    private fun recomputeConversationMemory() {
        val rows = _messages.value
        if (rows.isEmpty()) {
            _conversationMemory.value = ConversationMemoryBar()
            return
        }
        val lastQuestion = rows.asReversed()
            .firstOrNull { it.role == "user" && !isMetaUserMessage(it.text) }
            ?.text
            ?.trim()
            .orEmpty()
        val lastTopic = inferLastTopic(rows, _messageEvidence.value)
        _conversationMemory.value = ConversationMemoryBar(
            lastTopic = lastTopic,
            lastQuestion = lastQuestion
        )
    }

    private fun isMetaUserMessage(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return true
        return clean.startsWith("🎤 Voice message")
    }

    private fun inferLastTopic(
        rows: List<Message>,
        evidenceByMessageId: Map<Long, ChatResult>
    ): String {
        for (msg in rows.asReversed()) {
            if (msg.role != "assistant") continue
            if (msg.text.startsWith("---- New conversation")) continue
            val details = evidenceByMessageId[msg.id] ?: continue
            val topic = formatTopic(details.intent, details.mode, details.timeScope)
            if (topic.isNotBlank()) return topic
        }
        return ""
    }

    private fun formatTopic(intent: String, mode: String, timeScope: String): String {
        val key = intent.trim().lowercase().ifEmpty { mode.trim().lowercase() }
        val base = when (key) {
            "location_history" -> "Places visited"
            "location" -> "Current location"
            "summary" -> "Recap"
            "plans", "llm_plans" -> "Plans"
            "phone_status" -> "Phone status"
            "personal_question" -> "Personal question"
            "statement" -> "Memory capture"
            "general", "llm_general" -> "General chat"
            else -> ""
        }
        if (base.isBlank()) return ""
        val scope = friendlyScope(timeScope)
        return if (scope.isBlank()) base else "$base ($scope)"
    }

    private fun friendlyScope(timeScope: String): String {
        val clean = timeScope.trim().lowercase().replace('_', ' ')
        if (clean.isBlank() || clean == "none") return ""
        return clean
    }
}
