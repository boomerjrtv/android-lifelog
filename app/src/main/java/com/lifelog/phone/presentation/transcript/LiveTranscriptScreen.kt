package com.lifelog.phone.presentation.transcript

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.ui.theme.AccentBlue
import com.lifelog.phone.ui.theme.DarkBorder
import com.lifelog.phone.ui.theme.DarkSurface
import com.lifelog.phone.ui.theme.DarkSurfaceVariant
import com.lifelog.phone.ui.theme.TextMuted
import com.lifelog.phone.ui.theme.TextPrimary
import com.lifelog.phone.ui.theme.TextSecondary
import androidx.core.content.ContextCompat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranscriptScreen(
    viewModel: LiveTranscriptViewModel,
) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val status by viewModel.status.collectAsState()
    val speakerOptions by viewModel.speakerOptions.collectAsState()
    val activeSpeakerSlot by viewModel.activeSpeakerSlot.collectAsState()
    var editingItem by remember { mutableStateOf<LiveTranscriptItem?>(null) }
    var speakerInput by remember { mutableStateOf("") }
    var mergeFrom by remember { mutableStateOf("") }
    var mergeTo by remember { mutableStateOf("") }
    val livePartial by viewModel.livePartial.collectAsState()
    val recognizerAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    var hasRecordAudioPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudioPermission = granted
        viewModel.setRecognizerStatus(
            if (granted) "Background mic active" else "Microphone permission is required for live transcript"
        )
    }

    LaunchedEffect(Unit) {
        viewModel.startLiveRefresh()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopLiveRefresh() }
    }

    DisposableEffect(hasRecordAudioPermission) {
        viewModel.setRecognizerStatus(
            if (hasRecordAudioPermission) "Background mic active" else "Microphone permission is required for live transcript"
        )
        onDispose {}
    }

    DisposableEffect(context, hasRecordAudioPermission, recognizerAvailable) {
        if (!hasRecordAudioPermission || !recognizerAvailable) {
            onDispose {}
        } else {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            }

            fun restart() {
                runCatching { recognizer.startListening(listenIntent) }
                    .onFailure { viewModel.setRecognizerStatus("Live preview unavailable") }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    viewModel.setRecognizerStatus("Live preview active")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val best = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (best.isNotBlank()) viewModel.setLivePartial(best)
                }

                override fun onResults(results: Bundle?) {
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (best.isNotBlank()) {
                        viewModel.setLivePartial(best)
                        viewModel.commitTranscript(best)
                    } else {
                        viewModel.setLivePartial("")
                    }
                    restart()
                }

                override fun onError(error: Int) {
                    val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (!noSpeech) {
                        viewModel.setRecognizerStatus("Live preview error: $error")
                    }
                    viewModel.setLivePartial("")
                    restart()
                }
            })

            restart()

            onDispose {
                viewModel.setLivePartial("")
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }

    LaunchedEffect(hasRecordAudioPermission) {
        if (!hasRecordAudioPermission) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Live transcript", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    if (lastUpdated.isBlank()) "Waiting for transcript events..." else "Updated $lastUpdated",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(if (isLoading) "Syncing" else "Feed") })
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.refreshNow() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentBlue)
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                color = if (status.startsWith("Error")) MaterialTheme.colorScheme.error else TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (!hasRecordAudioPermission) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone access")
            }
        }

        if (livePartial.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkBorder),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Listening now", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(livePartial, color = TextPrimary, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Current speaker slot", color = TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("", "S1", "S2", "S3").forEach { slot ->
                val label = if (slot.isBlank()) "Off" else slot
                FilterChip(
                    selected = activeSpeakerSlot.trim().equals(slot, ignoreCase = true),
                    onClick = { viewModel.setActiveSpeakerSlot(slot) },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, DarkBorder),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Merge speaker labels", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = mergeFrom,
                        onValueChange = { mergeFrom = it },
                        singleLine = true,
                        label = { Text("From") },
                        placeholder = { Text("e.g. consumer") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    OutlinedTextField(
                        value = mergeTo,
                        onValueChange = { mergeTo = it },
                        singleLine = true,
                        label = { Text("To") },
                        placeholder = { Text("e.g. Michael") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                if (speakerOptions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        speakerOptions.take(4).forEach { candidate ->
                            FilterChip(
                                selected = mergeFrom.trim().equals(candidate, ignoreCase = true),
                                onClick = { mergeFrom = candidate },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }
                Button(
                    onClick = { viewModel.mergeSpeakerLabels(mergeFrom, mergeTo) },
                    enabled = mergeFrom.trim().isNotEmpty() && mergeTo.trim().isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Merge labels")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DarkBorder),
                    ) {
                        Text(
                            "No transcripts yet. Speak near the phone and this feed should populate in real time.",
                            color = TextSecondary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.text, color = TextPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        val who = item.speakerId.ifBlank { "Unlabeled" }
                        Text("Speaker: $who", color = TextSecondary, fontSize = 12.sp)
                        if (item.speakerClusterId.isNotBlank()) {
                            Text("Cluster: ${item.speakerClusterId}", color = TextMuted, fontSize = 12.sp)
                        }
                        Text("Time: ${item.ts}", color = TextMuted, fontSize = 12.sp)
                        if (item.sourceType.isNotBlank()) {
                            Text("Source: ${item.sourceType}", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                editingItem = item
                                speakerInput = item.speakerId
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Set speaker")
                        }
                    }
                }
            }
        }
    }

    if (editingItem != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingItem = null },
            containerColor = DarkSurface,
            title = { Text("Assign speaker", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = speakerInput,
                        onValueChange = { speakerInput = it },
                        singleLine = true,
                        label = { Text("Speaker name") },
                        placeholder = { Text("e.g. Michael") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant,
                            cursorColor = AccentBlue,
                            focusedLabelColor = AccentBlue,
                            unfocusedLabelColor = TextSecondary,
                        ),
                    )
                    if (speakerOptions.isNotEmpty()) {
                        Text("Known speakers", color = TextMuted, fontSize = 12.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            speakerOptions.take(8).forEach { candidate ->
                                FilterChip(
                                    selected = speakerInput.trim().equals(candidate, ignoreCase = true),
                                    onClick = { speakerInput = candidate },
                                    label = { Text(candidate) },
                                )
                            }
                        }
                    }
                    Text("Quick tags", color = TextMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("S1", "S2", "S3").forEach { candidate ->
                            FilterChip(
                                selected = speakerInput.trim().equals(candidate, ignoreCase = true),
                                onClick = { speakerInput = candidate },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val item = editingItem
                        if (item != null) viewModel.assignSpeaker(item, speakerInput)
                        editingItem = null
                    },
                    enabled = speakerInput.trim().isNotEmpty(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) { Text("Cancel") }
            },
        )
    }
}
