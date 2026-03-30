package com.lifelog.phone.presentation.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.Prefs
import com.lifelog.phone.data.Message
import com.lifelog.phone.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    baseUrl: String,
    viewModel: ChatViewModel,
    onVoiceRecord: (onComplete: (ByteArray) -> Unit) -> Unit,
    onConversationModeStart: (
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onConversationModeStop: () -> Unit,
    onConversationAssistantReply: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val messageEvidence by viewModel.messageEvidence.collectAsState()
    val conversationMemory by viewModel.conversationMemory.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var messageText by remember { mutableStateOf("") }
    var isConversationMode by remember { mutableStateOf(false) }
    var liveTranscript by remember { mutableStateOf("") }
    var conversationStatus by remember { mutableStateOf("Idle") }
    var assistantPreview by remember { mutableStateOf("") }
    var correctionTarget by remember { mutableStateOf<Message?>(null) }
    var correctionWrong by remember { mutableStateOf("") }
    var correctionCorrect by remember { mutableStateOf("") }
    var correctionReplaceEverywhere by remember { mutableStateOf(false) }
    var evidenceTarget by remember { mutableStateOf<Message?>(null) }
    var playingEvidenceAudio by remember { mutableStateOf(false) }
    var currentlyPlayingMessageId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val isAudioPlaying by viewModel.isAudioPlaying.collectAsState()

    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotEmpty()) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    correctionTarget?.let { target ->
        val wrong = correctionWrong.trim()
        val correct = correctionCorrect.trim()
        val canApply = wrong.isNotEmpty() && correct.isNotEmpty() && !wrong.equals(correct, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { correctionTarget = null },
            title = { Text("Fix Response") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = target.text,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 6
                    )
                    Text(
                        text = "What part is wrong?",
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = correctionWrong,
                        onValueChange = { correctionWrong = it },
                        label = { Text("Wrong text") },
                        minLines = 1,
                        maxLines = 3
                    )
                    OutlinedTextField(
                        value = correctionCorrect,
                        onValueChange = { correctionCorrect = it },
                        label = { Text("Replace with") },
                        minLines = 1,
                        maxLines = 3
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !correctionReplaceEverywhere,
                            onClick = { correctionReplaceEverywhere = false },
                            label = { Text("This reply") }
                        )
                        FilterChip(
                            selected = correctionReplaceEverywhere,
                            onClick = { correctionReplaceEverywhere = true },
                            label = { Text("Replace everywhere") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.applyEdit(
                            baseUrl = baseUrl,
                            target = target,
                            wrong = correctionWrong,
                            correct = correctionCorrect,
                            editedText = "",
                            replaceEverywhere = correctionReplaceEverywhere
                        ) { status ->
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                        correctionTarget = null
                    },
                    enabled = canApply
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { correctionTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    evidenceTarget?.let { target ->
        val details = messageEvidence[target.id]
        AlertDialog(
            onDismissRequest = { evidenceTarget = null },
            title = { Text("Answer Evidence") },
            text = {
                if (details == null) {
                    Text("No evidence metadata is available for this message yet.", color = TextSecondary)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Audio player controls at the top (always visible, no scroll needed)
                        if (isAudioPlaying && playingEvidenceAudio) {
                            AudioPlayerControls(
                                viewModel = viewModel,
                                onSave = {
                                    viewModel.saveAudioClip()
                                    Toast.makeText(context, "Audio saved to Documents", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Scrollable evidence content
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            EvidenceMetaRow("Mode", details.mode.ifBlank { "-" })
                            EvidenceMetaRow("Intent", details.intent.ifBlank { "-" })
                            EvidenceMetaRow("Time Scope", details.timeScope.ifBlank { "-" })
                            if (details.windowStart.isNotBlank() || details.windowEnd.isNotBlank()) {
                                EvidenceMetaRow(
                                    "Window",
                                    "${details.windowStart.ifBlank { "?" }} -> ${details.windowEnd.ifBlank { "?" }}"
                                )
                            }
                            if (details.reason.isNotBlank()) {
                                EvidenceMetaRow("Reason", details.reason)
                            }
                            if (details.evidence.isEmpty()) {
                                Text("No retrieval lines were attached to this response.", color = TextSecondary, fontSize = 12.sp)
                            } else {
                                Text("Evidence lines", color = TextPrimary, fontSize = 12.sp)
                                details.evidence.forEachIndexed { idx, row ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = "${idx + 1}. ${row.ts.ifBlank { "--" }} • ${row.source.ifBlank { "evidence" }}",
                                                    color = AccentBlue,
                                                    fontSize = 11.sp
                                                )
                                                Text(row.text, color = TextPrimary, fontSize = 12.sp)
                                            }
                                            val isAudioSource = row.source.startsWith("SPRINT") ||
                                                              row.source.startsWith("VOICE") ||
                                                              row.source.startsWith("PHONEWAV")
                                            if (isAudioSource) {
                                                IconButton(
                                                    onClick = {
                                                        Toast.makeText(context, "Playing source audio...", Toast.LENGTH_SHORT).show()
                                                        playingEvidenceAudio = true
                                                        viewModel.playAudio(row.source, row.ts)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = "Play audio",
                                                        tint = AccentGreen,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    playingEvidenceAudio = false
                    evidenceTarget = null
                }) { Text("Close") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        AnimatedVisibility(
            visible = conversationMemory.lastTopic.isNotBlank() || conversationMemory.lastQuestion.isNotBlank()
        ) {
            ConversationMemoryCard(
                memory = conversationMemory,
                onReuseLastQuestion = { messageText = it }
            )
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "LifeLog",
                                style = MaterialTheme.typography.headlineLarge,
                                color = AccentBlue
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Ask me anything about your day",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            items(messages) { message ->
                val hasEvidence = messageEvidence[message.id] != null
                MessageBubble(
                    message = message,
                    viewModel = viewModel,
                    isAudioPlaying = isAudioPlaying,
                    currentlyPlayingMessageId = currentlyPlayingMessageId,
                    onCurrentlyPlayingIdChange = { currentlyPlayingMessageId = it },
                    onCorrectionClick = { selected ->
                        correctionTarget = selected
                        correctionWrong = ""
                        correctionCorrect = ""
                        correctionReplaceEverywhere = false
                    },
                    onEvidenceClick = if (hasEvidence) {
                        { selected -> evidenceTarget = selected }
                    } else {
                        null
                    },
                    onPlayTTS = { text -> viewModel.playTTS(text) }
                )
            }
            if (isLoading) {
                item {
                    TypingIndicator()
                }
            }
        }

        // Input bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = DarkSurface,
            tonalElevation = 2.dp
        ) {
            if (isConversationMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen)
                            )
                            Text("Voice conversation", color = TextPrimary, fontSize = 14.sp)
                        }
                        Text(
                            "Stop",
                            color = AccentRed,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                isConversationMode = false
                                liveTranscript = ""
                                assistantPreview = ""
                                conversationStatus = "Idle"
                                onConversationModeStop()
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "You: " + if (liveTranscript.isNotBlank()) liveTranscript else "Listening...",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "AI: " + if (assistantPreview.isNotBlank()) assistantPreview else conversationStatus,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 3
                    )
                }
            } else if (isRecording) {
                RecordingBar(
                    onStop = { viewModel.stopRecording() }
                )
            } else {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Voice button
                    FilledIconButton(
                        onClick = {
                            viewModel.startRecording()
                            onVoiceRecord { audioData ->
                                viewModel.sendVoiceMessage(baseUrl, audioData)
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AccentGreen.copy(alpha = 0.15f),
                            contentColor = AccentGreen
                        )
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice", modifier = Modifier.size(22.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            isConversationMode = true
                            liveTranscript = ""
                            assistantPreview = ""
                            conversationStatus = "Listening..."
                            viewModel.startNewConversation()
                            onConversationModeStart(
                                { partial ->
                                    liveTranscript = partial
                                    conversationStatus = "Listening..."
                                },
                                { finalText ->
                                    val txt = finalText.trim()
                                    if (txt.isNotBlank()) {
                                        conversationStatus = "Sending..."
                                        liveTranscript = txt
                                        viewModel.sendMessage(baseUrl, txt, voice = true) { reply ->
                                            assistantPreview = reply
                                            onConversationAssistantReply(reply)
                                            liveTranscript = ""
                                            conversationStatus = "Listening..."
                                        }
                                    }
                                },
                                { err ->
                                    conversationStatus = err
                                }
                            )
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AccentBlue.copy(alpha = 0.15f),
                            contentColor = AccentBlue
                        )
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Conversation mode", modifier = Modifier.size(22.dp))
                    }

                    // Text input
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = {
                            Text("Message LifeLog...", color = TextMuted)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue.copy(alpha = 0.5f),
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkBackground,
                            unfocusedContainerColor = DarkBackground,
                            cursorColor = AccentBlue
                        ),
                        maxLines = 4,
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                    )

                    // Send button
                    FilledIconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                viewModel.sendMessage(baseUrl, messageText.trim())
                                messageText = ""
                            }
                        },
                        enabled = !isLoading && messageText.isNotBlank(),
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AccentBlue,
                            contentColor = TextPrimary,
                            disabledContainerColor = AccentBlue.copy(alpha = 0.3f),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationMemoryCard(
    memory: ConversationMemoryBar,
    onReuseLastQuestion: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Conversation memory", color = AccentBlue, fontSize = 11.sp)
            if (memory.lastTopic.isNotBlank()) {
                Text(
                    text = "Last topic: ${memory.lastTopic}",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            if (memory.lastQuestion.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Last question: ${memory.lastQuestion}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Use",
                        color = AccentGreen,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onReuseLastQuestion(memory.lastQuestion) }
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    viewModel: ChatViewModel,
    isAudioPlaying: Boolean,
    currentlyPlayingMessageId: Long?,
    onCurrentlyPlayingIdChange: (Long?) -> Unit,
    onCorrectionClick: ((Message) -> Unit)? = null,
    onEvidenceClick: ((Message) -> Unit)? = null,
    onPlayTTS: ((String) -> Unit)? = null
) {
    if (message.role == "assistant" && message.text.startsWith("---- New conversation")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = message.text,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        return
    }

    val isUser = message.role == "user"
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    val tsLabel = formatMessageTimestamp(message.timestamp)
    val bubbleShape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = alignment,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            // Assistant Avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("LL", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            // Model indicator
            if (!isUser) {
                val context = LocalContext.current
                val prefs = Prefs(context)
                val modelLabel = when (prefs.aiProvider()) {
                    "Gemini" -> "GEMINI"
                    "Ollama" -> "OLLAMA"
                    "Zai" -> "ZAI"
                    else -> "ASSISTANT"
                }
                Text(
                    text = modelLabel,
                    color = AccentBlue.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
                )
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(bubbleShape)
                    .background(if (isUser) UserBubble else AssistantBubble)
                    .border(1.dp, if (isUser) Color.White.copy(alpha = 0.1f) else DarkBorder, bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }

            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = tsLabel, color = TextMuted, fontSize = 10.sp)
                
                if (!isUser && onEvidenceClick != null) {
                    IconButton(
                        onClick = { onEvidenceClick(message) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Evidence", tint = AccentGreen.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                    }
                }
                
                if (!isUser && onCorrectionClick != null) {
                    IconButton(
                        onClick = { onCorrectionClick(message) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Fix", tint = AccentBlue.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                    }
                }

                // TTS button for assistant messages
                if (!isUser && !message.text.startsWith("---- New conversation") && onPlayTTS != null) {
                    val currentContext = LocalContext.current
                    val isThisMessagePlaying = currentlyPlayingMessageId == message.id && isAudioPlaying
                    IconButton(
                        onClick = {
                            if (isThisMessagePlaying) {
                                // Pause
                                viewModel.pauseAudio()
                            } else if (currentlyPlayingMessageId == message.id) {
                                // Resume
                                viewModel.resumeAudio()
                            } else {
                                // Start new TTS
                                Toast.makeText(currentContext, "Playing TTS...", Toast.LENGTH_SHORT).show()
                                onCurrentlyPlayingIdChange(message.id)
                                onPlayTTS(message.text)
                            }
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            if (isThisMessagePlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isThisMessagePlaying) "Pause TTS" else "Play as TTS",
                            tint = AccentBlue.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

            }
        }
        
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            // User Avatar (optional, or just spacing)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(UserBubble.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = TextPrimary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AudioPlayerControls(
    viewModel: ChatViewModel,
    onSave: () -> Unit
) {
    val isPlaying by viewModel.isAudioPlaying.collectAsState()
    val position by viewModel.audioPosition.collectAsState()
    val duration by viewModel.audioDuration.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Progress bar
        if (duration > 0) {
            Slider(
                value = position.toFloat(),
                onValueChange = { viewModel.seekAudio(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            // Time display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${formatSeconds(position)}", color = TextSecondary, fontSize = 11.sp)
                Text("${formatSeconds(duration)}", color = TextSecondary, fontSize = 11.sp)
            }
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rewind 10s
            IconButton(onClick = {
                val newPos = (position - 10000).coerceAtLeast(0)
                viewModel.seekAudio(newPos)
            }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, "Rewind", tint = AccentBlue, modifier = Modifier
                    .size(20.dp)
                    .rotate(180f))
            }

            // Play/Pause
            IconButton(onClick = {
                if (isPlaying) viewModel.pauseAudio() else viewModel.resumeAudio()
            }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = AccentGreen,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Fast forward 10s
            IconButton(onClick = {
                val newPos = (position + 10000).coerceAtMost(duration)
                viewModel.seekAudio(newPos)
            }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, "Fast forward", tint = AccentBlue, modifier = Modifier.size(20.dp))
            }

            // Save
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.GraphicEq, "Save", tint = AccentOrange, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun formatSeconds(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

@Composable
private fun EvidenceMetaRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp)
    }
}

private fun formatMessageTimestamp(tsMs: Long): String {
    val now = Date()
    val target = Date(tsMs)
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    return if (dayFmt.format(now) == dayFmt.format(target)) {
        SimpleDateFormat("h:mm a", Locale.US).format(target)
    } else {
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(target)
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(AssistantBubble)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(index * 200)
                        ),
                        label = "dot$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TextSecondary.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingBar(onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(AccentRed.copy(alpha = pulseAlpha))
            )
            Text(
                "Recording...",
                color = TextPrimary,
                fontSize = 15.sp
            )
        }

        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AccentRed.copy(alpha = 0.15f),
                contentColor = AccentRed
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
        }
    }
}
