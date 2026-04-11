package com.lifelog.phone.presentation.speaker

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.data.remote.LifeLogApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val DarkBg = Color(0xFF0F0F0F)
private val CardBg = Color(0xFF1C1C1E)
private val AccentBlue = Color(0xFF4A9EFF)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextSecondary = Color(0xFF9A9A9A)

data class SpeakerQuestion(
    val tempId: String,
    val clusterSize: Int,
    val sampleTs: String,
    val snippets: List<String>,
)

@Composable
fun SpeakerReviewScreen(
    tempId: String,
    api: LifeLogApi,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<SpeakerQuestion?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tempId) {
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = api.settingsRepository.cachedLifeLogSyncUrl.trim().trimEnd('/')
                val token = api.settingsRepository.cachedLifeLogSyncToken
                val reqBuilder = Request.Builder().url("$baseUrl/phone/speaker/pending").get()
                if (token.isNotEmpty()) reqBuilder.addHeader("X-Phone-Token", token)
                OkHttpClient().newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val p = JSONObject(body).optJSONObject("pending") ?: return@use
                    if (p.optString("temp_id") != tempId) return@use
                    val arr = p.optJSONArray("snippets")
                    val snippets = buildList {
                        if (arr != null) for (i in 0 until arr.length()) {
                            val s = arr.optString(i, "").trim()
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                    question = SpeakerQuestion(
                        tempId = tempId,
                        clusterSize = p.optInt("cluster_size", 0),
                        sampleTs = p.optString("sample_ts", ""),
                        snippets = snippets,
                    )
                }
            } catch (e: Exception) {
                Log.w("SpeakerReview", "load failed: ${e.message}")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Unknown voice", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary)
                }
            }

            val q = question
            if (q == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${q.clusterSize} recording${if (q.clusterSize != 1) "s" else ""}" +
                        if (q.sampleTs.isNotEmpty()) " · last heard ${q.sampleTs.take(10)}" else "",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        if (isPlaying) {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                            mediaPlayer = null
                            isPlaying = false
                        } else {
                            scope.launch {
                                isPlaying = true
                                try {
                                    val baseUrl = api.settingsRepository.cachedLifeLogSyncUrl.trim().trimEnd('/')
                                    val token = api.settingsRepository.cachedLifeLogSyncToken
                                    val url = "$baseUrl/phone/speaker/sample?temp_id=${q.tempId}"
                                    val mp = MediaPlayer().apply {
                                        setAudioAttributes(
                                            AudioAttributes.Builder()
                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                                .build()
                                        )
                                        val headers = if (token.isNotEmpty())
                                            mapOf("X-Phone-Token" to token) else emptyMap()
                                        setDataSource(context, android.net.Uri.parse(url), headers)
                                        setOnCompletionListener { isPlaying = false; mediaPlayer = null }
                                        setOnErrorListener { _, _, _ -> isPlaying = false; mediaPlayer = null; false }
                                        withContext(Dispatchers.IO) { prepare() }
                                    }
                                    mediaPlayer?.release()
                                    mediaPlayer = mp
                                    mp.start()
                                } catch (e: Exception) {
                                    Log.w("SpeakerReview", "playback error: ${e.message}")
                                    isPlaying = false
                                }
                            }
                        }
                    },
                    border = BorderStroke(1.dp, AccentBlue),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Stop" else "Play sample clip", color = AccentBlue, fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))

                if (q.snippets.isNotEmpty()) {
                    Text("What they said:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(q.snippets) { snippet ->
                            Surface(shape = RoundedCornerShape(8.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "\"$snippet\"",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text("No transcripts yet for this voice.", color = TextSecondary, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Who is this person?", color = TextSecondary) },
                    placeholder = { Text("Enter their name", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        val name = nameInput.trim()
                        if (name.isNotBlank() && !isSubmitting) {
                            scope.launch {
                                isSubmitting = true
                                doIdentify(api, q.tempId, name, onDismiss) { errorMsg = it; isSubmitting = false }
                            }
                        }
                    }),
                )

                errorMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isSubmitting = true
                                doSkip(api, q.tempId, onDismiss)
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0xFF555555)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Skip", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            if (name.isNotBlank()) {
                                scope.launch {
                                    isSubmitting = true
                                    doIdentify(api, q.tempId, name, onDismiss) { errorMsg = it; isSubmitting = false }
                                }
                            }
                        },
                        enabled = nameInput.isNotBlank() && !isSubmitting,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save name", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private suspend fun doIdentify(
    api: LifeLogApi,
    tempId: String,
    name: String,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val baseUrl = api.settingsRepository.cachedLifeLogSyncUrl.trim().trimEnd('/')
        val token = api.settingsRepository.cachedLifeLogSyncToken
        val payload = JSONObject().apply { put("temp_id", tempId); put("name", name) }.toString()
        val req = Request.Builder()
            .url("$baseUrl/phone/speaker/identify")
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (token.isNotEmpty()) req.addHeader("X-Phone-Token", token)
        OkHttpClient().newCall(req.build()).execute().use { resp ->
            if (resp.isSuccessful) withContext(Dispatchers.Main) { onDone() }
            else withContext(Dispatchers.Main) { onError("Server error ${resp.code}") }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { onError("Network error: ${e.message}") }
    }
}

private suspend fun doSkip(api: LifeLogApi, tempId: String, onDone: () -> Unit) =
    withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.settingsRepository.cachedLifeLogSyncUrl.trim().trimEnd('/')
            val token = api.settingsRepository.cachedLifeLogSyncToken
            val payload = JSONObject().apply { put("temp_id", tempId); put("skip", true) }.toString()
            val req = Request.Builder()
                .url("$baseUrl/phone/speaker/identify")
                .post(payload.toRequestBody("application/json".toMediaType()))
            if (token.isNotEmpty()) req.addHeader("X-Phone-Token", token)
            OkHttpClient().newCall(req.build()).execute().close()
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) { onDone() }
    }
