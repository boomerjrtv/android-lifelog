package com.lifelog.phone

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class Insight(
    val type: String,
    val time: String? = null,
    val text: String,
    val title: String? = null,
    val eventTime: String? = null,
    val location: String? = null
)

data class InsightsResponse(
    val ok: Boolean,
    val insights: Map<String, List<Insight>>
)

class Api(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String = prefs.baseUrl().trim().trimEnd('/')

    private fun authHeader(req: Request.Builder): Request.Builder {
        val tok = prefs.token().trim()
        if (tok.isNotEmpty()) {
            req.header("Authorization", "Bearer $tok")
        }
        return req
    }

    private fun isoNow(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return fmt.format(Instant.now())
    }

    fun outboxNext(): JSONObject? {
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/outbox2/next")
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val obj = JSONObject(body)
            val msg = obj.optJSONObject("msg")
            return msg
        }
    }

    fun outboxAck(id: Long) {
        val payload = JSONObject().put("id", id)
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/outbox2/ack")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { /* ignore */ }
    }

    fun phoneQuery(text: String) {
        val payload = JSONObject().put("text", text)
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/query")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { /* ignore */ }
    }

    fun telemetry(kind: String, data: JSONObject) {
        val payload = JSONObject()
            .put("ts", isoNow())
            .put("device_id", prefs.deviceId())
            .put("kind", kind)
            .put("data", data)
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/telemetry")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { /* ignore */ }
    }

    fun uploadWavQuery(wavBytes: ByteArray): String? {
        val body = wavBytes.toRequestBody("audio/wav".toMediaType())
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/upload_wav_query")
            .post(body)
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val s = res.body?.string() ?: return null
            val obj = JSONObject(s)
            if (obj.optBoolean("ok", false)) {
                val t = obj.optString("text", "").trim()
                return if (t.isNotEmpty()) t else null
            }
            return null
        }
    }

    fun uploadLoggerWav(wavBytes: ByteArray): Boolean {
        val body = wavBytes.toRequestBody("audio/wav".toMediaType())
        val req = authHeader(Request.Builder())
            .url("${baseUrl()}/phone/logger/upload_wav")
            .post(body)
            .build()
        client.newCall(req).execute().use { res ->
            val s = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                Log.w("Api", "uploadLoggerWav http=${res.code} body=${s.take(180)}")
                return false
            }
            val obj = JSONObject(s)
            return obj.optBoolean("ok", false)
        }
    }

    suspend fun getInsights(): InsightsResponse {
    val url = "${baseUrl()}/phone/insights"
    val request = authHeader(Request.Builder())
        .url(url)
        .get()
        .build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val ok = json.optBoolean("ok", false)
        
        if (!ok) {
            throw Exception("API returned error: $responseBody")
        }

        val insightsJson = json.optJSONObject("insights") ?: JSONObject()
        val insights = mutableMapOf<String, List<Insight>>()
        
        val keys = insightsJson.keys()
        for (key in keys) {
            val itemsArray = insightsJson.optJSONArray(key) ?: continue
            val items = mutableListOf<Insight>()
            
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                items.add(
                    Insight(
                        type = item.optString("type", ""),
                        time = item.optString("time", "").ifBlank { null },
                        text = item.optString("text", ""),
                        title = item.optString("title", "").ifBlank { null },
                        eventTime = item.optString("when", "").ifBlank { null },
                        location = item.optString("location", "").ifBlank { null }
                    )
                )
            }
            insights[key] = items
        }
        
        return InsightsResponse(ok = true, insights = insights)
    }
}
}
