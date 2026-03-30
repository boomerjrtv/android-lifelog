package com.lifelog.phone

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LifeLogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force set correct token for audio endpoints
        val prefs = Prefs(this)
        val correctToken = "da623d6a7ae22f6b6a80986f0ed6e11020d5df090cef8cb5c678968ab383c5f8"
        Log.i("LifeLogApp", "onCreate: Force-setting token to $correctToken")

        // Always set the token to ensure it's correct
        prefs.save(
            baseUrl = prefs.baseUrl(),
            token = correctToken,
            deviceId = prefs.deviceId(),
            picovoiceAccessKey = prefs.picovoiceAccessKey(),
            wakewordEnabled = prefs.wakewordEnabled(),
            loggerEnabled = prefs.loggerEnabled()
        )

        val newToken = prefs.token()
        Log.i("LifeLogApp", "onCreate: Token now set to $newToken")
        Log.i("LifeLogApp", "onCreate: Verification - token matches correct: ${newToken == correctToken}")
    }
}
