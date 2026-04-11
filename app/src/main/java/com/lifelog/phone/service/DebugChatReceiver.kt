package com.lifelog.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.LifeLogApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ADB-accessible broadcast receiver for headless chat testing.
 *
 * Send a query (returns immediately; result appears in logcat):
 *   adb shell am broadcast -a com.lifelog.phone.action.TEST_CHAT \
 *       -n com.lifelog.phone/.service.DebugChatReceiver \
 *       --es query "How was my day today?" --es tag q01
 *
 * Watch results:
 *   adb logcat -s LifeLogDebug
 *
 * Optional extras:
 *   --es tag "q01"   — label printed alongside result (for batch evals)
 */
@AndroidEntryPoint
class DebugChatReceiver : BroadcastReceiver() {

    @Inject lateinit var api: LifeLogApi
    @Inject lateinit var settingsRepository: SettingsRepository

    // Survives after onReceive() returns so Gemma can finish in background
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_CHAT) return

        val query = intent.getStringExtra(EXTRA_QUERY)
            ?.trim()
            ?.replace("__", "\n")
            ?.replace('_', ' ')
            .orEmpty()
        val tag   = intent.getStringExtra(EXTRA_TAG)?.trim().orEmpty()
        val label = if (tag.isNotEmpty()) "[$tag] " else ""

        if (query.isEmpty()) {
            Log.w(TAG, "TEST_CHAT: no query extra provided")
            return
        }

        val sessionId = "debug_${UUID.randomUUID().toString().replace("-", "")}"
        val baseUrl   = settingsRepository.cachedLifeLogSyncUrl
            .ifBlank { settingsRepository.lifeLogSyncUrl }
        val startMs   = System.currentTimeMillis()

        Log.i(TAG, "${label}QUERY: $query")
        Log.i(TAG, "${label}baseUrl=${baseUrl.ifBlank { "(local-only)" }}")

        // Launch fire-and-forget: onReceive returns immediately so ADB doesn't block.
        // The coroutine scope keeps running; logcat gets the reply when Gemma finishes.
        scope.launch {
            try {
                val result = api.chatWithMeta(
                    baseUrl   = baseUrl,
                    text      = query,
                    sessionId = sessionId,
                    voice     = false
                )
                val elapsedMs = System.currentTimeMillis() - startMs

                result.onSuccess { chat ->
                    Log.i(TAG, "${label}REPLY [${elapsedMs}ms] model=${chat.model} mode=${chat.mode} intent=${chat.intent}: ${chat.reply}")
                    if (chat.evidence.isNotEmpty()) {
                        Log.i(TAG, "${label}EVIDENCE (${chat.evidence.size} items):")
                        chat.evidence.take(5).forEach { ev ->
                            Log.i(TAG, "  [${ev.source}] ${ev.ts}: ${ev.text.take(120)}")
                        }
                    }
                    if (chat.reason.isNotBlank()) {
                        Log.i(TAG, "${label}REASON: ${chat.reason}")
                    }
                }.onFailure { err ->
                    Log.e(TAG, "${label}ERROR [${elapsedMs}ms]: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "${label}EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    companion object {
        const val ACTION_TEST_CHAT = "com.lifelog.phone.action.TEST_CHAT"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TAG   = "tag"
        private const val TAG = "LifeLogDebug"
    }
}
