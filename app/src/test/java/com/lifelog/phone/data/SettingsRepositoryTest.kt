package com.lifelog.phone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun lifeLogSyncUrl_defaultsToEmpty() {
        val repository = SettingsRepository(context)

        assertEquals("", repository.lifeLogSyncUrl)
        assertFalse(repository.isConfigured)
    }

    @Test
    fun lifeLogSyncUrl_setterIsIgnoredInOnDeviceMode() {
        val repository = SettingsRepository(context)

        repository.lifeLogSyncUrl = "  api.example.com/  "

        assertEquals("", repository.lifeLogSyncUrl)
        assertEquals("", repository.cachedLifeLogSyncUrl)
    }

    @Test
    fun lifeLogSyncUrl_blankRemainsBlankWithoutFallback() {
        val repository = SettingsRepository(context)

        repository.lifeLogSyncUrl = "   "

        assertEquals("", repository.lifeLogSyncUrl)
        assertEquals("", repository.cachedLifeLogSyncUrl)
        assertFalse(repository.isConfigured)
    }

    @Test
    fun lifeLogSyncUrl_tryCloudflareValueIsClearedOnInit() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("lifelog_sync_url", "https://foo.trycloudflare.com").commit()

        val repository = SettingsRepository(context)

        assertEquals("", repository.lifeLogSyncUrl)
        assertEquals("", repository.cachedLifeLogSyncUrl)
        assertFalse(repository.isConfigured)
    }
}
