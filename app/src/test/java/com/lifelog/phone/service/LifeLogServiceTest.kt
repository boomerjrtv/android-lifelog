package com.lifelog.phone.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lifelog.phone.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LifeLogServiceTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun resolvedSyncBaseUrl_returnsBlankWhenNoUrlConfigured() {
        val repository = SettingsRepository(context)
        val service = LifeLogService().apply {
            settingsRepository = repository
        }

        assertEquals("", service.resolvedSyncBaseUrl())
    }

    @Test
    fun resolvedSyncBaseUrl_ignoresCachedValueInOnDeviceMode() {
        val repository = SettingsRepository(context)
        repository.lifeLogSyncUrl = "https://cached.example.com/"

        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("lifelog_sync_url", "https://persisted.example.com")
            .commit()

        val service = LifeLogService().apply {
            settingsRepository = repository
        }

        assertEquals("", service.resolvedSyncBaseUrl())
    }

    @Test
    fun resolvedSyncBaseUrl_ignoresPrefsFallbackInOnDeviceMode() {
        val repository = SettingsRepository(context)

        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("lifelog_sync_url", "api.example.com/")
            .commit()

        val service = LifeLogService().apply {
            settingsRepository = repository
        }

        assertEquals("", service.resolvedSyncBaseUrl())
    }

    @Test
    fun resolvedSyncBaseUrl_returnsBlankForDeprecatedTunnelUrl() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("lifelog_sync_url", "https://foo.trycloudflare.com")
            .commit()

        val repository = SettingsRepository(context)
        val service = LifeLogService().apply {
            settingsRepository = repository
        }

        assertEquals("", service.resolvedSyncBaseUrl())
    }
}
