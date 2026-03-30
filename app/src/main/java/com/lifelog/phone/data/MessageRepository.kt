package com.lifelog.phone.data

import android.util.Log
import com.lifelog.phone.data.local.MessageDao
import com.lifelog.phone.data.local.MessageEntity
import com.lifelog.phone.data.remote.ChatResult
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.data.remote.TextReplaceResult
import com.lifelog.phone.data.remote.VoiceConversation
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val api: LifeLogApi,
    private val settingsRepository: SettingsRepository
) {
    private var lastSyncedConversationId = settingsRepository.cachedConversationSyncId

    suspend fun getAll(): List<Message> =
        messageDao.getAll().map { Message(it.id, it.role, it.text, it.timestamp, it.stem) }

    suspend fun insert(message: Message): Message {
        val rowId = messageDao.insert(MessageEntity(0, message.role, message.text, message.timestamp, message.stem))
        return message.copy(id = rowId)
    }

    suspend fun insertIfNotRecent(role: String, text: String, windowMs: Long = 180_000L): Message? {
        val cleanRole = role.trim().ifEmpty { "assistant" }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null
        val now = System.currentTimeMillis()
        val exists = messageDao.existsWithinWindow(
            role = cleanRole,
            text = cleanText,
            fromTs = now - windowMs,
            toTs = now + windowMs,
        )
        if (exists) return null
        val rowId = messageDao.insert(MessageEntity(0, cleanRole, cleanText, now))
        return Message(id = rowId, role = cleanRole, text = cleanText, timestamp = now)
    }

    suspend fun clear() = messageDao.clearAll()

    suspend fun sendChat(baseUrl: String, text: String, sessionId: String = "", voice: Boolean = false): Result<ChatResult> =
        api.chatWithMeta(baseUrl, text, sessionId = sessionId, voice = voice)

    suspend fun sendVoice(baseUrl: String, audio: ByteArray, sessionId: String = ""): Result<Pair<ChatResult, String>> =
        api.voiceQuery(baseUrl, audio, sessionId = sessionId)

    suspend fun capturePendingMealFromChat(baseUrl: String, text: String): Boolean {
        val pendingMeal = settingsRepository.cachedPendingMealKey.trim().lowercase()
        val pendingAt = settingsRepository.cachedPendingMealTs
        if (pendingMeal.isEmpty() || pendingAt <= 0L) return false
        val ageMs = System.currentTimeMillis() - pendingAt
        if (ageMs < 0L || ageMs > PENDING_MEAL_MAX_AGE_MS) {
            settingsRepository.clearPendingMealPrompt()
            return false
        }
        val clean = text.trim()
        if (!looksLikeMealAnswer(clean)) return false
        val day = LocalDate.now().toString()
        val saved = api.submitMealResponse(
            baseUrl = baseUrl,
            mealKey = pendingMeal,
            answer = clean,
            day = day
        ).isSuccess
        if (saved) {
            settingsRepository.clearPendingMealPrompt()
        }
        return saved
    }

    suspend fun replacePhraseInMessage(messageId: Long, wrong: String, correct: String): Boolean {
        if (messageId <= 0L) return false
        val row = messageDao.getById(messageId) ?: return false
        val replaced = replaceWithVariants(row.text, wrong, correct)
        if (replaced == row.text) return false
        return messageDao.updateText(messageId, replaced) > 0
    }

    suspend fun updateMessageText(messageId: Long, newText: String): Boolean {
        if (messageId <= 0L) return false
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return false
        return messageDao.updateText(messageId, trimmed) > 0
    }

    suspend fun replacePhraseAcrossLocalMessages(wrong: String, correct: String): Int {
        var total = 0
        for ((w, c) in caseVariants(wrong, correct)) {
            total += messageDao.replaceTextGlobal(w, c)
        }
        return total
    }

    suspend fun replacePhraseEverywhere(baseUrl: String, wrong: String, correct: String): Result<TextReplaceResult> =
        api.replaceTextEverywhere(baseUrl, wrong, correct, addAlias = true)

    suspend fun cleanupDuplicateMessages(): Int = messageDao.deleteExactDuplicates()

    suspend fun syncVoiceConversations(baseUrl: String): Int {
        return try {
            val result = api.getRecentConversations(baseUrl, lastSyncedConversationId)
            val conversations: List<VoiceConversation> = result.getOrElse { exception ->
                Log.e("MessageRepository", "Failed to sync conversations: ${exception.message}")
                return 0
            }

            var addedCount = 0
            var maxSeenId = lastSyncedConversationId
            val voiceAssistantDedupeWindowMs = 20_000L
            for (conv in conversations.sortedBy { it.id }) {
                if (conv.id <= lastSyncedConversationId) continue

                val source = conv.source.trim().lowercase()
                if (source == "chat_text") {
                    if (conv.id > maxSeenId) maxSeenId = conv.id
                    continue
                }

                val timestamp = parseIsoTimestamp(conv.ts)
                var insertedForConversation = false
                val convStem = if (conv.source.startsWith("voice_")) conv.source.removePrefix("voice_") else ""

                if (conv.query.isNotEmpty()) {
                    val exists = messageDao.existsExact(
                        role = "user",
                        text = conv.query,
                        timestamp = timestamp
                    )
                    if (!exists) {
                        messageDao.insert(MessageEntity(0, "user", conv.query, timestamp, convStem))
                        insertedForConversation = true
                    }
                }
                if (conv.reply.isNotEmpty()) {
                    val replyTs = timestamp + 1
                    val existsExact = messageDao.existsExact(
                        role = "assistant",
                        text = conv.reply,
                        timestamp = replyTs
                    )
                    val existsNearbyVoiceAssistant = if (!existsExact && source == "chat_voice") {
                        messageDao.existsWithinWindow(
                            role = "assistant",
                            text = conv.reply,
                            fromTs = replyTs - voiceAssistantDedupeWindowMs,
                            toTs = replyTs + voiceAssistantDedupeWindowMs
                        )
                    } else {
                        existsExact
                    }
                    if (!existsNearbyVoiceAssistant) {
                        messageDao.insert(MessageEntity(0, "assistant", conv.reply, replyTs))
                        insertedForConversation = true
                    }
                }

                if (insertedForConversation) addedCount++
                if (conv.id > maxSeenId) maxSeenId = conv.id
            }
            lastSyncedConversationId = maxSeenId
            settingsRepository.conversationSyncId = maxSeenId
            Log.i("MessageRepository", "Synced $addedCount new voice conversations (last_id=$lastSyncedConversationId)")
            addedCount
        } catch (e: Exception) {
            Log.e("MessageRepository", "Error syncing conversations: ${e.message}", e)
            0
        }
    }

    private fun caseVariants(wrong: String, correct: String): List<Pair<String, String>> {
        val out = linkedSetOf<Pair<String, String>>()
        out.add(wrong to correct)
        out.add(wrong.lowercase() to correct.lowercase())
        out.add(wrong.replaceFirstChar { it.titlecase() } to correct.replaceFirstChar { it.titlecase() })
        out.add(wrong.uppercase() to correct.uppercase())
        return out.filter { it.first.isNotBlank() }
    }

    private fun replaceWithVariants(text: String, wrong: String, correct: String): String {
        var out = text
        for ((w, c) in caseVariants(wrong, correct)) {
            out = out.replace(w, c)
        }
        return out
    }

    private fun parseIsoTimestamp(iso: String): Long {
        val raw = iso.trim()
        if (raw.isEmpty()) return System.currentTimeMillis()
        return try {
            try {
                return java.time.Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                val dt = java.time.LocalDateTime.parse(raw)
                return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            )
            for (format in formats) {
                try {
                    val date = format.parse(raw)
                    if (date != null) return date.time
                } catch (e: Exception) {
                    // Try next format
                }
            }
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun looksLikeMealAnswer(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > 220) return false
        if (clean.contains('?')) return false
        val low = clean.lowercase()
        val questionStarts = listOf(
            "what ", "where ", "when ", "why ", "how ",
            "did ", "do ", "does ", "is ", "are ", "can ",
            "could ", "should ", "would ", "will "
        )
        if (questionStarts.any { low.startsWith(it) }) return false
        return low.any { it.isLetter() }
    }

    private companion object {
        const val PENDING_MEAL_MAX_AGE_MS = 3L * 60L * 60L * 1000L
    }
}
