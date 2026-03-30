package com.lifelog.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextView.BufferType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val REQ_WIFI = 5181
    private var chatHistory: MutableList<ChatMessage> = mutableListOf()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ChatMessage(
        val role: String,
        val text: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        startLifeLogService()

        setContentView(R.layout.activity_chat)

        loadChatHistory()
        refreshChatUI()

        setupButtons()
    }

    private fun setupButtons() {
        val btnDashboard = findViewById<Button>(R.id.btnDashboard)
        val btnVoice = findViewById<Button>(R.id.btnVoiceChat)
        val txtMessage = findViewById<EditText>(R.id.txtMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)

        btnDashboard.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        btnVoice.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                sendVoiceChat()
            }
        }

        btnSend.setOnClickListener {
            val message = txtMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                runOnUiThread {
                    txtMessage.text = Editable.Factory().newEditable("")
                }
                CoroutineScope(Dispatchers.IO).launch {
                    sendChatMessage(message)
                }
            }
        }
    }

    private fun addToChat(message: ChatMessage) {
        chatHistory.add(message)
        saveChatHistory()
        runOnUiThread {
            refreshChatUI()
        }
    }

    private fun refreshChatUI() {
        val container = findViewById<LinearLayout>(R.id.chatHistory)
        container.removeAllViews()
        chatHistory.forEach { msg ->
            addMessageToUI(container, msg)
        }
    }

    private fun addMessageToUI(container: LinearLayout, message: ChatMessage) {
        val containerRow = LinearLayout(this)
        containerRow.orientation = LinearLayout.VERTICAL
        containerRow.setPadding(8, 4, 8, 4)
        containerRow.addView(createModelIndicator(message.role))

        val msgView = TextView(this)
        val prefix = if (message.role == "user") "You" else "LifeLog"
        msgView.text = "$prefix: ${message.text}"

        val bgColor = if (message.role == "user") "#E3F2FD" else "#F3F4F6"
        val textColor = if (message.role == "user") "#FFFFFF" else "#1A1A2A"

        msgView.setBackgroundColor(android.graphics.Color.parseColor(bgColor))
        msgView.setTextColor(android.graphics.Color.parseColor(textColor))
        msgView.setPadding(24, 16, 24, 16)
        msgView.textSize = 16f

        containerRow.addView(msgView)
        container.addView(containerRow)
    }

    private fun createModelIndicator(role: String): TextView {
        val indicator = TextView(this)
        if (role == "assistant") {
            val prefs = Prefs(this)
            val model = prefs.aiProvider()
            val modelText = when (model) {
                "Custom" -> prefs.customProvider().take(3).uppercase()
                else -> model.take(3).uppercase()
            }
            indicator.text = "[$modelText]"
            indicator.textSize = 10f
            indicator.setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            indicator.setPadding(8, 2, 8, 2)
            indicator.setBackgroundColor(android.graphics.Color.parseColor("#374151"))
        }
        return indicator
    }

    private fun loadChatHistory() {
        val saved = getSharedPreferences("lifelog_phone", Context.MODE_PRIVATE)
                .getString("chat_history", null)

        if (saved.isNullOrEmpty()) return

        try {
            val jsonArray = JSONArray(saved)
            chatHistory.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                chatHistory.add(ChatMessage(
                    obj.getString("role"),
                    obj.getString("text")
                ))
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Failed to load chat history: ${e.message}")
        }
    }

    private fun saveChatHistory() {
        val recentMessages = chatHistory.takeLast(50)
        val jsonArray = JSONArray()
        recentMessages.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("text", msg.text)
            jsonArray.put(obj)
        }

        getSharedPreferences("lifelog_phone", Context.MODE_PRIVATE)
            .edit()
            .putString("chat_history", jsonArray.toString())
            .apply()
    }

    private fun sendChatMessage(message: String) {
        try {
            val jsonBody = JSONObject().apply {
                put("text", message)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("${prefs.baseUrl().trimEnd('/')}/chat")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            val resp = client.newCall(req).execute()

            if (resp.isSuccessful) {
                val responseBody = resp.body?.string() ?: ""
                val jsonResp = JSONObject(responseBody)

                val reply = jsonResp.optString("reply", "")
                val mode = jsonResp.optString("mode", "")

                Log.d("ChatActivity", "Got reply: $reply (mode: $mode)")

                addToChat(ChatMessage("assistant", reply))
            } else {
                Log.e("ChatActivity", "Chat failed: ${resp.code}")
                addToChat(ChatMessage("assistant", "Error: ${resp.code}"))
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Chat error: ${e.message}")
            addToChat(ChatMessage("assistant", "Error: ${e.message}"))
        }
    }

    private fun sendVoiceChat() {
        try {
            val jsonBody = JSONObject().apply {
                put("text", "Start voice conversation")
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("${prefs.baseUrl().trimEnd('/')}/phone/voice/query")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            val resp = client.newCall(req).execute()

            if (resp.isSuccessful) {
                val responseBody = resp.body?.string() ?: ""
                val jsonResp = JSONObject(responseBody)
                Log.d("ChatActivity", "Voice chat queued: $jsonResp")

                runOnUiThread {
                    addToChat(ChatMessage("assistant", "Voice chat started! Say something..."))
                }
            } else {
                Log.e("ChatActivity", "Voice chat failed: ${resp.code}")
                runOnUiThread {
                    addToChat(ChatMessage("assistant", "Error: Failed to start voice chat"))
                }
            }

        } catch (e: Exception) {
            Log.e("ChatActivity", "Voice chat error: ${e.message}")
            runOnUiThread {
                addToChat(ChatMessage("assistant", "Error: ${e.message}"))
            }
        }
    }

    private fun startLifeLogService() {
        try {
            startForegroundService(Intent(this, LifeLogService::class.java))
        } catch (e: Exception) {
            Log.w("ChatActivity", "Failed to start service: ${e.message}")
        }
    }
}
