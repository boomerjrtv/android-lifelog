package com.lifelog.phone

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("lifelog_phone", Context.MODE_PRIVATE)

    fun baseUrl(): String = sp.getString("base_url", "https://breath-vary-guitar-facilitate.trycloudflare.com") ?: ""
    fun token(): String = sp.getString("token", "da623d6a7ae22f6b6a80986f0ed6e11020d5df090cef8cb5c678968ab383c5f8") ?: ""
    fun deviceId(): String = sp.getString("device_id", "pixel-10-pro-xl") ?: ""
    fun picovoiceAccessKey(): String = sp.getString("picovoice_access_key", "") ?: ""
    fun wakewordEnabled(): Boolean = sp.getBoolean("wakeword_enabled", false)
    fun loggerEnabled(): Boolean = sp.getBoolean("logger_enabled", true)
    fun autoStart(): Boolean = sp.getBoolean("auto_start", true)

    fun isSetupComplete(): Boolean = sp.getBoolean("setup_complete", false)
    fun dashboardUrl(): String = sp.getString("dashboard_url", "https://fiber-housing-fiber-stanley.trycloudflare.com") ?: ""
    fun setSetupComplete(done: Boolean) {
        sp.edit().putBoolean("setup_complete", done).apply()
    }

    // AI Provider settings
    fun aiProvider(): String = sp.getString("ai_provider", "Ollama") ?: "Ollama"
    fun customProvider(): String = sp.getString("custom_provider", "") ?: ""
    fun customUrl(): String = sp.getString("custom_url", "http://localhost:11434") ?: "http://localhost:11434"
    fun customModel(): String = sp.getString("custom_model", "") ?: ""
    fun customApiKey(): String = sp.getString("custom_api_key", "") ?: ""

    fun save(
        baseUrl: String,
        token: String,
        deviceId: String,
        picovoiceAccessKey: String,
        wakewordEnabled: Boolean,
        loggerEnabled: Boolean
    ) {
        sp.edit()
            .putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("device_id", deviceId.trim())
            .putString("picovoice_access_key", picovoiceAccessKey.trim())
            .putBoolean("wakeword_enabled", wakewordEnabled)
            .putBoolean("logger_enabled", loggerEnabled)
            .apply()
    }
}
