package com.lifelog.phone.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val onDeviceOnly = false
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private companion object {
        const val PREF_PENDING_MEAL_KEY = "pending_meal_key"
        const val PREF_PENDING_MEAL_TS = "pending_meal_ts"
        const val PREF_ASSISTANT_SYSTEM_PROMPT = "assistant_system_prompt"
        const val PREF_LIFELOG_SYNC_URL = "lifelog_sync_url"
        const val PREF_LIFELOG_SYNC_TOKEN = "lifelog_sync_token"
        const val PREF_ACTIVE_SPEAKER_SLOT = "active_speaker_slot"
        val LEGACY_ASSISTANT_SYSTEM_PROMPT = """
            Be an evidence-first life assistant.
            For personal memory questions, prioritize timestamped logs and retrieval evidence over generic facts.
            For day/week recaps, summarize in chronological order and group by date when useful.
            Distinguish between events that happened vs things that were only mentioned; do not treat mentions as completed events.
            If evidence is partial, answer with what is known and clearly mark uncertainty.
            Keep responses concise, practical, and natural for spoken conversation.
            Avoid filler and avoid repeating low-signal telemetry unless it changes the answer.
            When asked for suggestions, base them on observed patterns and current context, not assumptions.
        """.trimIndent()
        val DEFAULT_ASSISTANT_SYSTEM_PROMPT = """
            You are LifeLog, a warm and practical voice assistant.
            Sound natural and conversational, like a thoughtful teammate speaking out loud.
            Use contractions and plain wording; avoid stiff or clinical tone.
            Prefer short, clear answers first, then add detail only when asked.
            Keep default responses to about 2-5 sentences unless the user asks for depth.
            For recap questions, give one short takeaway sentence plus up to 3 bullets.
            Do not dump raw JSON, internal fields, or coordinate blobs unless asked.
            Use the user's logs as primary evidence for personal memory questions.
            For recaps, keep chronology clear and separate confirmed events from mentions.
            If evidence is partial, say what is known and what is uncertain in plain language.
            Avoid robotic phrasing, boilerplate disclaimers, and repetitive telemetry lists.
            If no useful evidence exists, say that clearly and offer one next best action.
            When useful, end with one helpful follow-up question.
        """.trimIndent()
    }

    @Volatile var cachedLifeLogSyncUrl: String = run {
        val raw = prefs.getString(PREF_LIFELOG_SYNC_URL, "") ?: ""
        if (raw.contains(".trycloudflare.com", ignoreCase = true)) "" else raw.trim().trimEnd('/')
    }
        private set

    @Volatile var cachedLifeLogSyncToken: String = prefs.getString(PREF_LIFELOG_SYNC_TOKEN, "") ?: ""
        private set

    @Volatile var cachedConversationSyncId: Int = prefs.getInt("conversation_sync_id", 0)
        private set

    @Volatile var cachedOpenEnrollAfterSetup: Boolean = prefs.getBoolean("open_enroll_after_setup", false)
        private set

    @Volatile var cachedPendingMealKey: String = prefs.getString(PREF_PENDING_MEAL_KEY, "") ?: ""
        private set

    @Volatile var cachedPendingMealTs: Long = prefs.getLong(PREF_PENDING_MEAL_TS, 0L)
        private set

    @Volatile var cachedAssistantSystemPrompt: String =
        prefs.getString(PREF_ASSISTANT_SYSTEM_PROMPT, DEFAULT_ASSISTANT_SYSTEM_PROMPT)
            ?: DEFAULT_ASSISTANT_SYSTEM_PROMPT
        private set

    @Volatile var cachedAiProvider: String = prefs.getString("ai_provider", "Ollama") ?: "Ollama"
        private set

    @Volatile var cachedActiveSpeakerSlot: String = prefs.getString(PREF_ACTIVE_SPEAKER_SLOT, "") ?: ""
        private set

    init {
        val migratedUrl = sanitizeSyncUrl(prefs.getString(PREF_LIFELOG_SYNC_URL, "") ?: "")
        if (migratedUrl != cachedLifeLogSyncUrl) {
            prefs.edit().putString(PREF_LIFELOG_SYNC_URL, migratedUrl).commit()
            cachedLifeLogSyncUrl = migratedUrl
        }
        if (cachedAssistantSystemPrompt.isBlank() || cachedAssistantSystemPrompt == LEGACY_ASSISTANT_SYSTEM_PROMPT) {
            prefs.edit().putString(PREF_ASSISTANT_SYSTEM_PROMPT, DEFAULT_ASSISTANT_SYSTEM_PROMPT).commit()
            cachedAssistantSystemPrompt = DEFAULT_ASSISTANT_SYSTEM_PROMPT
        }
    }


    private fun normalizeUrl(value: String): String {
        var cleaned = value.trim().trimEnd('/')
        if (cleaned.isNotEmpty() && !cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "http://$cleaned"
        }
        return cleaned
    }

    private fun isDeprecatedTunnelUrl(value: String): Boolean {
        val clean = value.trim().lowercase()
        return clean.contains(".trycloudflare.com")
    }

    private fun sanitizeSyncUrl(value: String): String {
        if (onDeviceOnly) return ""
        val cleaned = normalizeUrl(value)
        if (cleaned.isEmpty()) return ""
        return if (isDeprecatedTunnelUrl(cleaned)) "" else cleaned
    }

    private fun isLoopbackUrl(value: String): Boolean {
        val clean = value.trim().lowercase()
        return clean.contains("://localhost") || clean.contains("://127.0.0.1") || clean.contains("://0.0.0.0")
    }

    var lifeLogSyncUrl: String
        get() = sanitizeSyncUrl(prefs.getString(PREF_LIFELOG_SYNC_URL, "") ?: "")
        set(value) {
            val cleaned = sanitizeSyncUrl(value)
            prefs.edit().putString(PREF_LIFELOG_SYNC_URL, cleaned).commit()
            cachedLifeLogSyncUrl = cleaned
        }

    var lifeLogSyncToken: String
        get() = prefs.getString(PREF_LIFELOG_SYNC_TOKEN, "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString(PREF_LIFELOG_SYNC_TOKEN, cleaned).commit()
            cachedLifeLogSyncToken = cleaned
        }

    var conversationSyncId: Int
        get() = prefs.getInt("conversation_sync_id", 0)
        set(value) {
            val safe = value.coerceAtLeast(0)
            prefs.edit().putInt("conversation_sync_id", safe).commit()
            cachedConversationSyncId = safe
        }

    var openEnrollAfterSetup: Boolean
        get() = prefs.getBoolean("open_enroll_after_setup", false)
        set(value) {
            prefs.edit().putBoolean("open_enroll_after_setup", value).commit()
            cachedOpenEnrollAfterSetup = value
        }

    var assistantSystemPrompt: String
        get() = prefs.getString(PREF_ASSISTANT_SYSTEM_PROMPT, DEFAULT_ASSISTANT_SYSTEM_PROMPT)
            ?.ifBlank { DEFAULT_ASSISTANT_SYSTEM_PROMPT }
            ?: DEFAULT_ASSISTANT_SYSTEM_PROMPT
        set(value) {
            val cleaned = value.trim().ifBlank { DEFAULT_ASSISTANT_SYSTEM_PROMPT }
            prefs.edit().putString(PREF_ASSISTANT_SYSTEM_PROMPT, cleaned).commit()
            cachedAssistantSystemPrompt = cleaned
        }

    var aiProvider: String
        get() = prefs.getString("ai_provider", "Ollama") ?: "Ollama"
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("ai_provider", cleaned).commit()
            cachedAiProvider = cleaned
        }

    var activeSpeakerSlot: String
        get() = prefs.getString(PREF_ACTIVE_SPEAKER_SLOT, "") ?: ""
        set(value) {
            val cleaned = value.trim().uppercase()
            prefs.edit().putString(PREF_ACTIVE_SPEAKER_SLOT, cleaned).commit()
            cachedActiveSpeakerSlot = cleaned
        }

    var customProvider: String
        get() = prefs.getString("custom_provider", "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("custom_provider", cleaned).commit()
            cachedCustomProvider = cleaned
        }

    var customUrl: String
        get() = prefs.getString("custom_url", "http://localhost:11434") ?: "http://localhost:11434"
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("custom_url", cleaned).commit()
            cachedCustomUrl = cleaned
        }

    var customModel: String
        get() = prefs.getString("custom_model", "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("custom_model", cleaned).commit()
            cachedCustomModel = cleaned
        }

    var customApiKey: String
        get() = prefs.getString("custom_api_key", "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("custom_api_key", cleaned).commit()
            cachedCustomApiKey = cleaned
        }

    @Volatile var cachedCustomProvider: String = prefs.getString("custom_provider", "") ?: ""
        private set
    @Volatile var cachedCustomUrl: String = prefs.getString("custom_url", "http://localhost:11434") ?: "http://localhost:11434"
        private set
    @Volatile var cachedCustomModel: String = prefs.getString("custom_model", "") ?: ""
        private set
    @Volatile var cachedCustomApiKey: String = prefs.getString("custom_api_key", "") ?: ""
        private set

    // Ollama settings
    var ollamaUrl: String
        get() = prefs.getString("ollama_url", "http://localhost:11434") ?: "http://localhost:11434"
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("ollama_url", cleaned).commit()
        }

    var ollamaModel: String
        get() = prefs.getString("ollama_model", "llama3") ?: "llama3"
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("ollama_model", cleaned).commit()
        }

    // Gemini settings
    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("gemini_api_key", cleaned).commit()
        }

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("gemini_model", cleaned).commit()
        }

    // Zai settings
    var zaiApiKey: String
        get() = prefs.getString("zai_api_key", "") ?: ""
        set(value) {
            val cleaned = value.trim()
            prefs.edit().putString("zai_api_key", cleaned).commit()
        }

    val isConfigured: Boolean
        get() = cachedLifeLogSyncUrl.isNotEmpty()

    fun recommendedAssistantPrompt(): String = DEFAULT_ASSISTANT_SYSTEM_PROMPT

    fun setPendingMealPrompt(mealKey: String, tsMs: Long = System.currentTimeMillis()) {
        val clean = mealKey.trim().lowercase()
        prefs.edit()
            .putString(PREF_PENDING_MEAL_KEY, clean)
            .putLong(PREF_PENDING_MEAL_TS, tsMs)
            .commit()
        cachedPendingMealKey = clean
        cachedPendingMealTs = tsMs
    }

    fun clearPendingMealPrompt() {
        prefs.edit()
            .remove(PREF_PENDING_MEAL_KEY)
            .remove(PREF_PENDING_MEAL_TS)
            .commit()
        cachedPendingMealKey = ""
        cachedPendingMealTs = 0L
    }
}
