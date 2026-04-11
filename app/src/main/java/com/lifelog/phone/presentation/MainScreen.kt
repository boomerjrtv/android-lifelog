package com.lifelog.phone.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.local.SpeakerProfileDao
import com.lifelog.phone.data.local.SpeakerProfileEntity
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.data.speaker.EcapaEmbeddingEngine
import com.lifelog.phone.presentation.auth.GoogleSignInService
import com.lifelog.phone.presentation.auth.PreLoginGateScreen
import com.lifelog.phone.presentation.chat.ChatScreen
import com.lifelog.phone.presentation.chat.ChatViewModel
import com.lifelog.phone.presentation.dashboard.DashboardScreen
import com.lifelog.phone.presentation.dashboard.DashboardViewModel
import com.lifelog.phone.presentation.insights.InsightsScreen
import com.lifelog.phone.presentation.audio.AudioRecorder
import com.lifelog.phone.presentation.transcript.LiveTranscriptScreen
import com.lifelog.phone.presentation.transcript.LiveTranscriptViewModel
import com.lifelog.phone.service.WakeWordService
import com.lifelog.phone.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.lifelog.phone.presentation.speaker.SpeakerReviewScreen
import com.lifelog.phone.service.LifeLogService
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val audioRecorder = AudioRecorder()
    private val chatViewModel: ChatViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val liveTranscriptViewModel: LiveTranscriptViewModel by viewModels()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var googleSignInService: GoogleSignInService
    @Inject lateinit var lifeLogApi: LifeLogApi
    @Inject lateinit var speakerProfileDao: SpeakerProfileDao
    @Inject lateinit var ecapaEmbeddingEngine: EcapaEmbeddingEngine


    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val auth = googleSignInService.handleSignInResult(result.data)
            if (auth.success) {
                Toast.makeText(this, "Signed in: ${auth.email ?: ""}", Toast.LENGTH_SHORT).show()
                googleSignInComplete.value = true
            } else {
                Toast.makeText(this, auth.error ?: "Google sign-in failed", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Google sign-in error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }
    private val calendarPermissionState = mutableStateOf(false)
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarPermissionState.value = granted
        if (granted) {
            Toast.makeText(this, "Calendar permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Calendar permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications disabled; reminders may not appear", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingVoiceCallback: ((ByteArray) -> Unit)? = null
    private var pendingRecordDurationMs: Long = 3000L
    private var speechRecognizer: SpeechRecognizer? = null
    private var singleShotBaseUrl: String = ""
    private var conversationEnabled = false
    private var conversationInFlight = false
    private var conversationNoSpeechErrors = 0
    private var conversationPausedForSilence = false
    private var onConversationPartial: ((String) -> Unit)? = null
    private var onConversationFinal: ((String) -> Unit)? = null
    private var onConversationError: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var conversationTts: TextToSpeech? = null
    private var conversationTtsReady = false
    private val wakeStatusText = mutableStateOf("Wakeword: Off (manual)")
    private val openChatRequest = mutableStateOf(false)
    private val googleSignInComplete = mutableStateOf(false)
    private val speakerReviewTempId = mutableStateOf<String?>(null)
    private var wakeStateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                conversationTts?.language = Locale.US
                conversationTtsReady = true
                conversationTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { finishConversationTurn() }
                    }
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { finishConversationTurn() }
                    }
                })
            }
        }

        // Handle ADB provisioning before starting service
        applyAdbProvisioning(intent)
        handleAdbTestTriggers(intent)
        handleNotificationChatSeed(intent)
        calendarPermissionState.value = hasCalendarPermission()
        requestNotificationPermissionIfNeeded()

        // Start background service
        val loggingIntent = Intent(this, com.lifelog.phone.service.LifeLogService::class.java).apply {
            action = com.lifelog.phone.service.LifeLogService.ACTION_TRANSCRIPTION_START
        }
        startForegroundService(loggingIntent)
        registerWakeStateReceiver()

        setContent {
            LifeLogTheme {
                MainApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainApp() {
        val context = LocalContext.current
        val openEnrollAfterSetup = settingsRepository.cachedOpenEnrollAfterSetup
        var selectedTab by remember { mutableIntStateOf(if (openEnrollAfterSetup) 4 else 0) }
        var showPreLoginGate by remember { mutableStateOf(true) }
        val baseUrl = settingsRepository.cachedLifeLogSyncUrl.ifBlank { settingsRepository.lifeLogSyncUrl }
        val shouldOpenChat = openChatRequest.value
        val shouldProceedAfterSignIn = googleSignInComplete.value
        val speakerReview = speakerReviewTempId.value
        
        LaunchedEffect(openEnrollAfterSetup) {
            if (openEnrollAfterSetup) {
                settingsRepository.openEnrollAfterSetup = false
            }
        }
        LaunchedEffect(shouldOpenChat) {
            if (shouldOpenChat) {
                selectedTab = 0
                openChatRequest.value = false
            }
        }
        LaunchedEffect(shouldProceedAfterSignIn) {
            if (shouldProceedAfterSignIn) {
                showPreLoginGate = false
                googleSignInComplete.value = false
            }
        }
        LaunchedEffect(selectedTab) {
            if (selectedTab != 0 && conversationEnabled) {
                stopConversationMode()
            }
        }

        // Show speaker review screen when opened from notification
        if (speakerReview != null) {
            SpeakerReviewScreen(
                tempId = speakerReview,
                api = lifeLogApi,
                onDismiss = { speakerReviewTempId.value = null },
            )
            return
        }

        // Show pre-login gate if needed
        if (showPreLoginGate) {
            PreLoginGateScreen(
                api = lifeLogApi,
                baseUrl = baseUrl,
                googleSignInService = googleSignInService,
                onGoogleSignInClick = {
                    googleSignInLauncher.launch(googleSignInService.getSignInIntent())
                },
                onProceedToApp = {
                    showPreLoginGate = false
                }
            )
            return
        }

        Scaffold(
            containerColor = DarkBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (selectedTab) {
                                0 -> "LifeLog"
                                1 -> "Dashboard"
                                2 -> "Insights"
                                3 -> "Transcripts"
                                4 -> "Settings"
                                else -> "LifeLog"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { chatViewModel.clearChat() }) {
                                Icon(Icons.Default.DeleteSweep, "Clear chat", tint = TextSecondary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkSurface,
                        titleContentColor = TextPrimary
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = DarkSurface,
                    contentColor = TextPrimary,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Chat") },
                        label = { Text("Chat", fontSize = 12.sp) },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Dashboard", fontSize = 12.sp) },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Lightbulb, contentDescription = "Insights") },
                        label = { Text("Insights", fontSize = 12.sp) },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", fontSize = 12.sp) },
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Mic, contentDescription = "Transcripts") },
                        label = { Text("Transcripts", fontSize = 12.sp) },
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> {
                        LaunchedEffect(baseUrl) {
                            chatViewModel.setBaseUrl(baseUrl)
                        }
                        val wakeStatus by wakeStatusText
                        Box(modifier = Modifier.fillMaxSize()) {
                            ChatScreen(
                                baseUrl = baseUrl,
                                viewModel = chatViewModel,
                                onVoiceRecord = { _ ->
                                    singleShotBaseUrl = baseUrl
                                    startSingleShotVoice()
                                },
                                onConversationModeStart = { onPartial, onFinal, onError ->
                                    onConversationPartial = onPartial
                                    onConversationFinal = onFinal
                                    onConversationError = onError
                                    startConversationMode()
                                },
                                onConversationModeStop = {
                                    stopConversationMode()
                                },
                                onConversationAssistantReply = { reply ->
                                    speakConversationReply(reply)
                                }
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                                shape = RoundedCornerShape(999.dp),
                                color = AccentBlue.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Wakeword: $wakeStatus",
                                    color = AccentBlue,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    1 -> DashboardScreen(
                        baseUrl = baseUrl,
                        viewModel = dashboardViewModel,
                        onEnrollSpeaker = { speaker -> enrollSpeakerFromLogs(speaker) }
                    )
                    2 -> {
                        InsightsScreen(
                            api = lifeLogApi,
                            googleSignInService = googleSignInService,
                            onGoogleSignInClick = {
                                googleSignInLauncher.launch(googleSignInService.getSignInIntent())
                            }
                        )
                    }
                    3 -> LiveTranscriptScreen(viewModel = liveTranscriptViewModel)
                    4 -> SettingsContent()
                }
            }
        }
    }

    @Composable
    private fun SettingsContent() {
        val context = LocalContext.current
        var serverUrl by remember { mutableStateOf(settingsRepository.lifeLogSyncUrl) }
        var authToken by remember { mutableStateOf(settingsRepository.lifeLogSyncToken) }
        var assistantPrompt by remember { mutableStateOf(settingsRepository.assistantSystemPrompt) }
        var assistantPromptStatus by remember { mutableStateOf("") }
        var testStatus by remember { mutableStateOf("") }
        var isTesting by remember { mutableStateOf(false) }
        var wakeStatus by remember { mutableStateOf("Wakeword: Off") }
        var speakerName by remember { mutableStateOf("") }
        var speakerStatus by remember { mutableStateOf("") }
        var isLoadingSpeakers by remember { mutableStateOf(false) }
        var isEnrollingSpeaker by remember { mutableStateOf(false) }
        var speakerProfiles by remember { mutableStateOf<List<com.lifelog.phone.data.remote.SpeakerProfileItem>>(emptyList()) }
        var speakerKnownLabels by remember { mutableStateOf<List<com.lifelog.phone.data.remote.SpeakerKnownLabelItem>>(emptyList()) }
        val hasCalendarPermission = calendarPermissionState.value
        val scope = rememberCoroutineScope()
        val api = lifeLogApi

        LaunchedEffect(Unit) {
            val base = settingsRepository.cachedLifeLogSyncUrl.trim()
            if (base.isBlank()) return@LaunchedEffect
            isLoadingSpeakers = true
            val result = api.getSpeakerProfiles(base)
            if (result.isSuccess) {
                val payload = result.getOrNull()
                speakerProfiles = payload?.profiles ?: emptyList()
                speakerKnownLabels = payload?.knownLabels ?: emptyList()
                speakerStatus = if ((payload?.profiles?.size ?: 0) == 0) {
                    "No enrolled speakers yet. Enroll at least one voice."
                } else {
                    ""
                }
            } else {
                speakerStatus = "Speaker profiles unavailable: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            }
            isLoadingSpeakers = false
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Server section
            Text("Server", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("LifeLog Sync URL") },
                placeholder = { Text("https://api.example.com", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    cursorColor = AccentBlue,
                    focusedLabelColor = AccentBlue,
                    unfocusedLabelColor = TextSecondary
                )
            )

            OutlinedTextField(
                value = authToken,
                onValueChange = { authToken = it },
                label = { Text("LifeLog Token") },
                placeholder = { Text("PHONE_OUTBOX_TOKEN value", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    cursorColor = AccentBlue,
                    focusedLabelColor = AccentBlue,
                    unfocusedLabelColor = TextSecondary
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        settingsRepository.lifeLogSyncUrl = serverUrl
                        settingsRepository.lifeLogSyncToken = authToken
                        testStatus = "Saved!"
                        val base = settingsRepository.cachedLifeLogSyncUrl.trim()
                        if (base.isNotEmpty()) {
                            isLoadingSpeakers = true
                            scope.launch {
                                val result = api.getSpeakerProfiles(base)
                                if (result.isSuccess) {
                                    val payload = result.getOrNull()
                                    speakerProfiles = payload?.profiles ?: emptyList()
                                    speakerKnownLabels = payload?.knownLabels ?: emptyList()
                                    if (speakerProfiles.isEmpty()) {
                                        speakerStatus = "No enrolled speakers yet. Enroll at least one voice."
                                    }
                                } else {
                                    speakerStatus = "Speaker profiles unavailable: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                                }
                                isLoadingSpeakers = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = TextPrimary
                    )
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }

                OutlinedButton(
                    onClick = {
                        isTesting = true
                        testStatus = "Testing..."
                        scope.launch {
                            try {
                                val result = api.health(serverUrl.trim().trimEnd('/'))
                                testStatus = if (result.isSuccess && result.getOrNull() == true) {
                                    "Connected!"
                                } else {
                                    "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                                }
                            } catch (e: Exception) {
                                testStatus = "Error: ${e.message}"
                            }
                            isTesting = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isTesting && serverUrl.isNotBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentGreen
                    ),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AccentGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Test")
                }
            }

            if (testStatus.isNotEmpty()) {
                val statusColor = when {
                    testStatus.startsWith("Connected") || testStatus == "Saved!" -> AccentGreen
                    testStatus.startsWith("Testing") -> AccentOrange
                    else -> AccentRed
                }
                Text(testStatus, color = statusColor, fontSize = 14.sp)
            }

            HorizontalDivider(color = DarkBorder)

            // Assistant prompt section
            Text("Assistant", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Prompt override (applies to chat, voice button, conversation mode, and wakeword)",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = assistantPrompt,
                        onValueChange = { assistantPrompt = it },
                        label = { Text("Assistant prompt") },
                        placeholder = { Text("Optional: add style/tuning instructions", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 12,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            cursorColor = AccentBlue,
                            focusedLabelColor = AccentBlue,
                            unfocusedLabelColor = TextSecondary
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                settingsRepository.assistantSystemPrompt = assistantPrompt
                                assistantPrompt = settingsRepository.cachedAssistantSystemPrompt
                                assistantPromptStatus = "Prompt saved."
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentBlue.copy(alpha = 0.15f),
                                contentColor = AccentBlue
                            )
                        ) {
                            Text("Save Prompt", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                assistantPrompt = settingsRepository.recommendedAssistantPrompt()
                                settingsRepository.assistantSystemPrompt = assistantPrompt
                                assistantPromptStatus = "Recommended prompt applied."
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentGreen.copy(alpha = 0.15f),
                                contentColor = AccentGreen
                            )
                        ) {
                            Text("Use Recommended", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                assistantPrompt = ""
                                settingsRepository.assistantSystemPrompt = ""
                                assistantPromptStatus = "Prompt cleared."
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentRed.copy(alpha = 0.15f),
                                contentColor = AccentRed
                            )
                        ) {
                            Text("Clear", fontSize = 13.sp)
                        }
                    }
                    if (assistantPromptStatus.isNotEmpty()) {
                        val color = when {
                            assistantPromptStatus.contains("saved", ignoreCase = true) -> AccentBlue
                            assistantPromptStatus.contains("applied", ignoreCase = true) -> AccentGreen
                            assistantPromptStatus.contains("cleared", ignoreCase = true) -> AccentOrange
                            else -> TextSecondary
                        }
                        Text(assistantPromptStatus, color = color, fontSize = 12.sp)
                    }
                }
            }

            HorizontalDivider(color = DarkBorder)

            // Model Selection section
            Text("AI Provider", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Log.d("MainScreen", "Rendering model section, aiProvider=${settingsRepository.aiProvider}")
            var expanded by remember { mutableStateOf(false) }
            val models = listOf("On-Device", "Ollama", "Gemini", "Zai", "Custom")
            var selectedModel by remember { mutableStateOf(settingsRepository.aiProvider) }
            var showCustomConfig by remember { mutableStateOf(selectedModel == "Custom") }
            var customProvider by remember { mutableStateOf(settingsRepository.customProvider ?: "") }
            var customApiKey by remember { mutableStateOf(settingsRepository.customApiKey ?: "") }
            var customModel by remember { mutableStateOf(settingsRepository.customModel ?: "") }
            var customUrl by remember { mutableStateOf(settingsRepository.customUrl ?: "http://localhost:11434") }

            // Provider-specific settings
            var ollamaUrl by remember { mutableStateOf(settingsRepository.ollamaUrl ?: "http://localhost:11434") }
            var ollamaModel by remember { mutableStateOf(settingsRepository.ollamaModel ?: "llama3") }
            var geminiApiKey by remember { mutableStateOf(settingsRepository.geminiApiKey ?: "") }
            var geminiModel by remember { mutableStateOf(settingsRepository.geminiModel ?: "gemini-2.0-flash") }
            var zaiApiKey by remember { mutableStateOf(settingsRepository.zaiApiKey ?: "") }
            // Ollama model management state
            var ollamaInstalledModels by remember { mutableStateOf<List<String>>(emptyList()) }
            var ollamaPullStatus by remember { mutableStateOf("") }
            var ollamaPullPercent by remember { mutableStateOf(-1) }
            var ollamaIsPulling by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = {},
                            label = { Text("AI Provider") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = DarkBorder,
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                cursorColor = AccentBlue,
                                focusedLabelColor = AccentBlue,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        selectedModel = model
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Provider-specific configuration
                    when (selectedModel) {
                        "On-Device" -> {
                            Spacer(Modifier.height(8.dp))
                            var modelDownloaded by remember { mutableStateOf(api.isOnDeviceModelDownloaded()) }
                            val onDeviceModelSizeLabel = "%.1f GB".format(api.onDeviceModelSizeMb() / 1000.0)
                            var nanoAvailable by remember { mutableStateOf<Boolean?>(null) }
                            var isDownloading by remember { mutableStateOf(false) }
                            var downloadProgress by remember { mutableIntStateOf(0) }
                            var downloadStatus by remember { mutableStateOf("") }
                            var hfToken by remember { mutableStateOf("") }
                            LaunchedEffect(Unit) {
                                nanoAvailable = api.checkGeminiNanoAvailable()
                            }
                            // Gemini Nano status
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (nanoAvailable == true) AccentGreen.copy(alpha = 0.08f)
                                    else DarkSurface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        if (nanoAvailable == true) "✓" else "○",
                                        color = if (nanoAvailable == true) AccentGreen else TextSecondary,
                                        fontSize = 16.sp
                                    )
                                    Column {
                                        Text("Gemini Nano", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            if (nanoAvailable == null) "Checking..."
                                            else if (nanoAvailable == true) "Ready — built into your device"
                                            else "Not available (needs Pixel 8+ / Samsung S24+)",
                                            color = TextSecondary, fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // Gemma 4 E2B via LiteRT-LM — broad Android fallback
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (modelDownloaded) AccentGreen.copy(alpha = 0.08f) else DarkSurface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            if (modelDownloaded) "✓" else "○",
                                            color = if (modelDownloaded) AccentGreen else TextSecondary,
                                            fontSize = 16.sp
                                        )
                                        Column {
                                            Text("Gemma 4 E2B (LiteRT-LM)", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            Text(
                                                if (modelDownloaded) "Downloaded — offline Gemma 4 fallback"
                                                else "~$onDeviceModelSizeLabel · Apache 2.0 · works on any Android",
                                                color = TextSecondary, fontSize = 12.sp
                                            )
                                        }
                                    }
                                    if (!modelDownloaded) {
                                        OutlinedTextField(
                                            value = hfToken,
                                            onValueChange = { hfToken = it },
                                            label = { Text("HuggingFace Token (optional)") },
                                            placeholder = { Text("hf_...") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                unfocusedBorderColor = DarkBorder,
                                                cursorColor = AccentBlue
                                            )
                                        )
                                        Text(
                                            "Get a free token at huggingface.co/settings/tokens",
                                            color = TextSecondary, fontSize = 11.sp
                                        )
                                        Button(
                                            onClick = {
                                                if (!isDownloading) {
                                                    isDownloading = true
                                                    downloadStatus = "Starting download..."
                                                    scope.launch {
                                                        val result = api.downloadOnDeviceModel(hfToken.trim()) { pct ->
                                                            downloadProgress = pct
                                                            downloadStatus = "Downloading $pct%"
                                                        }
                                                        if (result.isSuccess) {
                                                            modelDownloaded = true
                                                            downloadStatus = "Download complete!"
                                                        } else {
                                                            downloadStatus = "Failed: ${result.exceptionOrNull()?.message}"
                                                        }
                                                        isDownloading = false
                                                    }
                                                }
                                            },
                                            enabled = !isDownloading,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary)
                                        ) {
                                            if (isDownloading) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(if (isDownloading) "Downloading..." else "Download Gemma 4 ($onDeviceModelSizeLabel)", fontSize = 13.sp)
                                        }
                                        if (isDownloading) {
                                            LinearProgressIndicator(
                                                progress = { downloadProgress / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = AccentBlue,
                                                trackColor = DarkBorder
                                            )
                                        }
                                        if (downloadStatus.isNotEmpty()) {
                                            val color = when {
                                                downloadStatus.startsWith("Download complete") -> AccentGreen
                                                downloadStatus.startsWith("Failed") -> AccentRed
                                                else -> AccentOrange
                                            }
                                            Text(downloadStatus, color = color, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            var speakerModelDownloaded by remember { mutableStateOf(api.isSpeakerModelDownloaded()) }
                            val speakerModelSizeLabel = "~${api.speakerModelSizeMb()} MB"
                            var speakerDownloadStatus by remember { mutableStateOf("") }
                            var speakerDownloadPct by remember { mutableIntStateOf(0) }
                            var speakerDownloading by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (speakerModelDownloaded) AccentGreen.copy(alpha = 0.08f) else DarkSurface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            if (speakerModelDownloaded) "✓" else "○",
                                            color = if (speakerModelDownloaded) AccentGreen else TextSecondary,
                                            fontSize = 16.sp
                                        )
                                        Column {
                                            Text("Speaker Embedding Model (Optional)", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            Text(
                                                if (speakerModelDownloaded) "Downloaded — improved speaker matching enabled"
                                                else "$speakerModelSizeLabel · app already works without this",
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                    if (!speakerModelDownloaded) {
                                        Text(
                                            "Optional accuracy pack. Skip this if you want zero setup.",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                        Button(
                                            onClick = {
                                                if (!speakerDownloading) {
                                                    speakerDownloading = true
                                                    speakerDownloadStatus = "Starting download..."
                                                    scope.launch {
                                                        val result = api.downloadSpeakerModel { pct ->
                                                            speakerDownloadPct = pct
                                                            speakerDownloadStatus = "Downloading $pct%"
                                                        }
                                                        if (result.isSuccess) {
                                                            speakerModelDownloaded = true
                                                            speakerDownloadStatus = "Download complete!"
                                                        } else {
                                                            speakerDownloadStatus = "Failed: ${result.exceptionOrNull()?.message}"
                                                        }
                                                        speakerDownloading = false
                                                    }
                                                }
                                            },
                                            enabled = !speakerDownloading,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary)
                                        ) {
                                            if (speakerDownloading) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(if (speakerDownloading) "Downloading..." else "Download Speaker Model ($speakerModelSizeLabel)", fontSize = 13.sp)
                                        }
                                        if (speakerDownloading) {
                                            LinearProgressIndicator(
                                                progress = { speakerDownloadPct / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = AccentBlue,
                                                trackColor = DarkBorder
                                            )
                                        }
                                        if (speakerDownloadStatus.isNotBlank()) {
                                            val color = when {
                                                speakerDownloadStatus.startsWith("Download complete") -> AccentGreen
                                                speakerDownloadStatus.startsWith("Failed") -> AccentRed
                                                else -> AccentOrange
                                            }
                                            Text(speakerDownloadStatus, color = color, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text(
                                            "No action needed. This is only for higher-accuracy speaker recognition.",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }

                            // ── Whisper on-device STT model ──────────────────────────────
                            Spacer(Modifier.height(8.dp))
                            var whisperDownloaded by remember { mutableStateOf(api.isWhisperDownloaded()) }
                            val whisperSizeLabel = "~${api.whisperSizeMb()} MB"
                            var whisperDownloadStatus by remember { mutableStateOf("") }
                            var whisperDownloadPct by remember { mutableIntStateOf(0) }
                            var whisperDownloading by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (whisperDownloaded) AccentGreen.copy(alpha = 0.08f) else DarkSurface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            if (whisperDownloaded) "✓" else "○",
                                            color = if (whisperDownloaded) AccentGreen else TextSecondary,
                                            fontSize = 16.sp
                                        )
                                        Column {
                                            Text("On-Device Voice Transcription (Whisper)", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            Text(
                                                if (whisperDownloaded) "Downloaded — mic button uses fully offline Whisper STT"
                                                else "$whisperSizeLabel · replaces Google STT with fully private on-device Whisper",
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                    if (!whisperDownloaded) {
                                        Button(
                                            onClick = {
                                                if (!whisperDownloading) {
                                                    whisperDownloading = true
                                                    whisperDownloadStatus = "Starting download..."
                                                    scope.launch {
                                                        val result = api.downloadWhisper { pct ->
                                                            whisperDownloadPct = pct
                                                            whisperDownloadStatus = "Downloading $pct%"
                                                        }
                                                        if (result.isSuccess) {
                                                            whisperDownloaded = true
                                                            whisperDownloadStatus = "Download complete!"
                                                        } else {
                                                            whisperDownloadStatus = "Failed: ${result.exceptionOrNull()?.message}"
                                                        }
                                                        whisperDownloading = false
                                                    }
                                                }
                                            },
                                            enabled = !whisperDownloading,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary)
                                        ) {
                                            if (whisperDownloading) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(if (whisperDownloading) "Downloading..." else "Download Whisper ($whisperSizeLabel)", fontSize = 13.sp)
                                        }
                                        if (whisperDownloading) {
                                            LinearProgressIndicator(
                                                progress = { whisperDownloadPct / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = AccentBlue,
                                                trackColor = DarkBorder
                                            )
                                        }
                                        if (whisperDownloadStatus.isNotBlank()) {
                                            val color = when {
                                                whisperDownloadStatus.startsWith("Download complete") -> AccentGreen
                                                whisperDownloadStatus.startsWith("Failed") -> AccentRed
                                                else -> AccentOrange
                                            }
                                            Text(whisperDownloadStatus, color = color, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text(
                                            "Mic button uses Whisper for private, offline speech recognition.",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                        "Ollama" -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ollamaUrl,
                                onValueChange = { ollamaUrl = it },
                                label = { Text("Ollama Server URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = DarkBorder,
                                    cursorColor = AccentBlue
                                )
                            )
                            OutlinedTextField(
                                value = ollamaModel,
                                onValueChange = { ollamaModel = it },
                                label = { Text("Model Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = DarkBorder,
                                    cursorColor = AccentBlue
                                )
                            )
                            // Quick-pick model chips
                            val quickModels = listOf("gemma4:4b", "gemma4:12b", "llama3.2:3b")
                            LaunchedEffect(ollamaUrl) {
                                try {
                                    val result = api.ollamaListModels()
                                    ollamaInstalledModels = result.getOrNull()?.map { it.name } ?: emptyList()
                                } catch (_: Exception) {}
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Quick pick:", color = TextSecondary, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                quickModels.forEach { model ->
                                    val isInstalled = ollamaInstalledModels.any {
                                        it == model || it.startsWith("$model:")
                                    }
                                    val isSelected = ollamaModel == model
                                    OutlinedButton(
                                        onClick = { ollamaModel = model },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) AccentBlue else if (isInstalled) AccentGreen else DarkBorder
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (isSelected) AccentBlue else if (isInstalled) AccentGreen else TextSecondary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(model, fontSize = 11.sp)
                                        if (isInstalled) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("✓", fontSize = 11.sp, color = AccentGreen)
                                        }
                                    }
                                }
                            }
                            // Download button — shown when selected model is not installed
                            val modelInstalled = ollamaInstalledModels.any {
                                it == ollamaModel || it.startsWith("$ollamaModel:")
                            }
                            if (!modelInstalled && ollamaModel.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (!ollamaIsPulling) {
                                            ollamaIsPulling = true
                                            ollamaPullStatus = "Starting download..."
                                            ollamaPullPercent = -1
                                            scope.launch {
                                                val capturedModel = ollamaModel
                                            val result = api.ollamaPullModel(capturedModel) { prog ->
                                                ollamaPullPercent = prog.percent
                                                ollamaPullStatus = when {
                                                    prog.isDone -> "Download complete!"
                                                    prog.isFailed -> "Download failed"
                                                    prog.percent >= 0 -> "Downloading ${prog.percent}%"
                                                    else -> prog.status
                                                }
                                                if (prog.isDone) {
                                                    ollamaInstalledModels = ollamaInstalledModels + capturedModel
                                                }
                                            }
                                                if (result.isFailure) {
                                                    ollamaPullStatus = "Error: ${result.exceptionOrNull()?.message}"
                                                }
                                                ollamaIsPulling = false
                                            }
                                        }
                                    },
                                    enabled = !ollamaIsPulling,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentBlue.copy(alpha = 0.15f),
                                        contentColor = AccentBlue,
                                        disabledContainerColor = AccentBlue.copy(alpha = 0.08f),
                                        disabledContentColor = AccentBlue.copy(alpha = 0.5f)
                                    )
                                ) {
                                    if (ollamaIsPulling) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = AccentBlue,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (ollamaIsPulling) "Downloading $ollamaModel..." else "Download $ollamaModel",
                                        fontSize = 13.sp
                                    )
                                }
                                if (ollamaIsPulling && ollamaPullPercent >= 0) {
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { ollamaPullPercent / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = AccentBlue,
                                        trackColor = DarkBorder
                                    )
                                }
                            }
                            if (ollamaPullStatus.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                val statusColor = when {
                                    ollamaPullStatus.startsWith("Download complete") -> AccentGreen
                                    ollamaPullStatus.startsWith("Error") || ollamaPullStatus.startsWith("Download failed") -> AccentRed
                                    else -> AccentOrange
                                }
                                Text(ollamaPullStatus, color = statusColor, fontSize = 12.sp)
                            }
                        }
                        "Gemini" -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = geminiApiKey,
                                onValueChange = { geminiApiKey = it },
                                label = { Text("Gemini API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = DarkBorder,
                                    cursorColor = AccentBlue
                                )
                            )
                            OutlinedTextField(
                                value = geminiModel,
                                onValueChange = { geminiModel = it },
                                label = { Text("Model Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = DarkBorder,
                                    cursorColor = AccentBlue
                                )
                            )
                        }
                        "Zai" -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = zaiApiKey,
                                onValueChange = { zaiApiKey = it },
                                label = { Text("Z.ai API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = DarkBorder,
                                    cursorColor = AccentBlue
                                )
                            )
                        }
                    }
                    if (selectedModel == "Custom") {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = customProvider,
                                    onValueChange = { customProvider = it },
                                    label = { Text("Provider Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = DarkBorder,
                                        cursorColor = AccentBlue
                                    )
                                )
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    label = { Text("Server URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = DarkBorder,
                                        cursorColor = AccentBlue
                                    )
                                )
                                OutlinedTextField(
                                    value = customModel,
                                    onValueChange = { customModel = it },
                                    label = { Text("Model Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = DarkBorder,
                                        cursorColor = AccentBlue
                                    )
                                )
                                OutlinedTextField(
                                    value = customApiKey,
                                    onValueChange = { customApiKey = it },
                                    label = { Text("API Key (if needed)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = DarkBorder,
                                        cursorColor = AccentBlue
                                    )
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            settingsRepository.aiProvider = selectedModel
                            // Save provider-specific settings
                            when (selectedModel) {
                                "Ollama" -> {
                                    settingsRepository.ollamaUrl = ollamaUrl
                                    settingsRepository.ollamaModel = ollamaModel
                                }
                                "Gemini" -> {
                                    settingsRepository.geminiApiKey = geminiApiKey
                                    settingsRepository.geminiModel = geminiModel
                                }
                                "Zai" -> {
                                    settingsRepository.zaiApiKey = zaiApiKey
                                }
                                "Custom" -> {
                                    settingsRepository.customProvider = customProvider
                                    settingsRepository.customUrl = customUrl
                                    settingsRepository.customModel = customModel
                                    settingsRepository.customApiKey = customApiKey
                                }
                            }
                            scope.launch {
                                try {
                                    val payload = when (selectedModel) {
                                        "Custom" -> mapOf(
                                            "provider" to customProvider,
                                            "url" to customUrl,
                                            "model" to customModel,
                                            "api_key" to customApiKey
                                        )
                                        else -> mapOf("provider" to selectedModel)
                                    }
                                    val syncBase = settingsRepository.cachedLifeLogSyncUrl.trim().trimEnd('/')
                                    api.updateSetting(syncBase, "ai_provider", selectedModel)
                                    api.updateSetting(syncBase, "custom_config", payload.toString())
                                    testStatus = "AI Provider saved!"
                                } catch (e: Exception) {
                                    testStatus = "Error saving AI Provider: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save AI Provider")
                    }
                }
            }

            HorizontalDivider(color = DarkBorder)
            
            // Service section
            Text("Service", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Background Service", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text("Continuous audio logging & heartbeat", color = TextSecondary, fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            val svc = Intent(this@MainActivity, com.lifelog.phone.service.LifeLogService::class.java)
                            stopService(svc)
                            startForegroundService(svc)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AccentBlue.copy(alpha = 0.15f),
                            contentColor = AccentBlue
                        )
                    ) {
                        Text("Restart", fontSize = 13.sp)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Wakeword Listener", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text("Manual on/off to avoid beep loops", color = TextSecondary, fontSize = 12.sp)
                        Text(wakeStatus, color = TextSecondary, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val svc = Intent(this@MainActivity, com.lifelog.phone.service.LifeLogService::class.java).apply {
                                    action = com.lifelog.phone.service.LifeLogService.ACTION_TRANSCRIPTION_START
                                }
                                startService(svc)
                                wakeStatus = "Wakeword: On"
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentGreen.copy(alpha = 0.15f),
                                contentColor = AccentGreen
                            )
                        ) {
                            Text("Start", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                val svc = Intent(this@MainActivity, com.lifelog.phone.service.LifeLogService::class.java).apply {
                                    action = com.lifelog.phone.service.LifeLogService.ACTION_TRANSCRIPTION_STOP
                                }
                                startService(svc)
                                stopService(Intent(this@MainActivity, com.lifelog.phone.service.WakeWordService::class.java))
                                wakeStatus = "Wakeword: Off"
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentRed.copy(alpha = 0.15f),
                                contentColor = AccentRed
                            )
                        ) {
                            Text("Stop", fontSize = 13.sp)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Calendar Sync", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasCalendarPermission) "Permission: Granted" else "Permission: Not granted",
                        color = if (hasCalendarPermission) AccentGreen else TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { requestCalendarPermission() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentBlue.copy(alpha = 0.15f),
                                contentColor = AccentBlue
                            )
                        ) {
                            Text(if (hasCalendarPermission) "Recheck Access" else "Grant Access", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                val svc = Intent(this@MainActivity, com.lifelog.phone.service.LifeLogService::class.java)
                                stopService(svc)
                                startForegroundService(svc)
                                testStatus = "Calendar sync scheduled"
                            },
                            enabled = hasCalendarPermission,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentGreen.copy(alpha = 0.15f),
                                contentColor = AccentGreen
                            )
                        ) {
                            Text("Sync Now", fontSize = 13.sp)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Voice Profiles", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enrolled speakers: ${speakerProfiles.size}",
                        color = if (speakerProfiles.isNotEmpty()) AccentGreen else TextSecondary,
                        fontSize = 12.sp
                    )
                    if (speakerProfiles.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val profileText = speakerProfiles.joinToString(", ") {
                            it.name.ifBlank { it.fileStem }
                        }
                        Text(
                            profileText.take(180),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    if (speakerKnownLabels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val knownText = speakerKnownLabels.take(8).joinToString(" | ") {
                            if (it.enrolled) it.name else "${it.name} (not enrolled)"
                        }
                        Text(
                            "Known labels: $knownText",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = speakerName,
                        onValueChange = { speakerName = it },
                        label = { Text("Speaker name") },
                        placeholder = { Text("Example: Ruthie", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            cursorColor = AccentBlue,
                            focusedLabelColor = AccentBlue,
                            unfocusedLabelColor = TextSecondary
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val base = serverUrl.trim().trimEnd('/')
                                if (base.isBlank()) {
                                    speakerStatus = "Save server URL before loading speaker profiles."
                                    return@FilledTonalButton
                                }
                                isLoadingSpeakers = true
                                scope.launch {
                                    val result = api.getSpeakerProfiles(base)
                                    if (result.isSuccess) {
                                        val payload = result.getOrNull()
                                        speakerProfiles = payload?.profiles ?: emptyList()
                                        speakerKnownLabels = payload?.knownLabels ?: emptyList()
                                        speakerStatus = "Speaker profiles refreshed."
                                    } else {
                                        speakerStatus = "Speaker profiles unavailable: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                                    }
                                    isLoadingSpeakers = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentBlue.copy(alpha = 0.15f),
                                contentColor = AccentBlue
                            ),
                            enabled = !isLoadingSpeakers && !isEnrollingSpeaker
                        ) {
                            if (isLoadingSpeakers) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = AccentBlue,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Refresh", fontSize = 13.sp)
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                val base = serverUrl.trim().trimEnd('/')
                                val target = speakerName.trim()
                                if (base.isBlank()) {
                                    speakerStatus = "Save server URL before enrolling."
                                    return@FilledTonalButton
                                }
                                if (target.isBlank()) {
                                    speakerStatus = "Enter a speaker name first."
                                    return@FilledTonalButton
                                }
                                speakerStatus = "Recording 10s sample for $target..."
                                pendingVoiceCallback = { audio ->
                                    scope.launch {
                                        if (audio.isEmpty()) {
                                            speakerStatus = "No audio captured. Try again."
                                            return@launch
                                        }
                                        isEnrollingSpeaker = true
                                        val enrollResult = api.enrollSpeaker(base, target, audio)
                                        if (enrollResult.isSuccess) {
                                            speakerStatus = "Enrolled $target."
                                            val refreshResult = api.getSpeakerProfiles(base)
                                            if (refreshResult.isSuccess) {
                                                val payload = refreshResult.getOrNull()
                                                speakerProfiles = payload?.profiles ?: emptyList()
                                                speakerKnownLabels = payload?.knownLabels ?: emptyList()
                                            }
                                        } else {
                                            speakerStatus = "Enroll failed: ${enrollResult.exceptionOrNull()?.message ?: "unknown error"}"
                                        }
                                        isEnrollingSpeaker = false
                                    }
                                }
                                requestAudioPermission(durationMs = 10000L)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AccentGreen.copy(alpha = 0.15f),
                                contentColor = AccentGreen
                            ),
                            enabled = !isLoadingSpeakers && !isEnrollingSpeaker
                        ) {
                            if (isEnrollingSpeaker) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = AccentGreen,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Record + Enroll", fontSize = 13.sp)
                            }
                        }
                    }
                    if (speakerStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val speakerStatusColor = when {
                            speakerStatus.startsWith("Enrolled") -> AccentGreen
                            speakerStatus.startsWith("Recording") -> AccentOrange
                            speakerStatus.startsWith("Speaker profiles refreshed") -> AccentBlue
                            else -> AccentRed
                        }
                        Text(speakerStatus, color = speakerStatusColor, fontSize = 12.sp)
                    }
                }
            }

            // Info section
            HorizontalDivider(color = DarkBorder)
            Text("About", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Version", color = TextSecondary, fontSize = 14.sp)
                        Text("1.0", color = TextPrimary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Server", color = TextSecondary, fontSize = 14.sp)
                        Text(
                            settingsRepository.cachedLifeLogSyncUrl.take(40),
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    private fun registerWakeStateReceiver() {
        wakeStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val state = intent?.getStringExtra(WakeWordService.EXTRA_STATE)?.trim().orEmpty()
                val detail = intent?.getStringExtra(WakeWordService.EXTRA_DETAIL)?.trim().orEmpty()
                if (state.isNotEmpty() || detail.isNotEmpty()) {
                    wakeStatusText.value = if (detail.isNotEmpty()) "$state: $detail" else state
                }
            }
        }
        wakeStateReceiver = receiver
        val filter = IntentFilter(WakeWordService.ACTION_WAKE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun requestAudioPermission(durationMs: Long = 3000L) {
        pendingRecordDurationMs = durationMs.coerceIn(1000L, 20000L)
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
            }
            else -> {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestCalendarPermission() {
        when {
            hasCalendarPermission() -> {
                calendarPermissionState.value = true
            }
            else -> {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    private fun handleAdbTestTriggers(intent: Intent?) {
        val chatQuery = intent?.getStringExtra("chat_query")?.trim().orEmpty()
        if (chatQuery.isNotEmpty()) {
            val url = settingsRepository.cachedLifeLogSyncUrl
                .ifBlank { settingsRepository.lifeLogSyncUrl }
            chatViewModel.sendMessage(url, chatQuery)
            Toast.makeText(this, "ADB query: $chatQuery", Toast.LENGTH_SHORT).show()
        }

        val evalQuery = intent?.getStringExtra("chat_eval_query")
            ?.trim()
            ?.replace("__", "\n")
            ?.replace('_', ' ')
            .orEmpty()
        if (evalQuery.isNotEmpty()) {
            val evalPrompt = intent?.getStringExtra("chat_eval_prompt")
                ?.trim()
                ?.replace("__", "\n")
                ?.replace('_', ' ')
                .orEmpty()
            val evalBaseUrl = intent?.getStringExtra("chat_eval_base_url")?.trim().orEmpty()
            lifecycleScope.launch {
                val url = evalBaseUrl.ifBlank {
                    settingsRepository.cachedLifeLogSyncUrl.ifBlank { settingsRepository.lifeLogSyncUrl }
                }
                val originalPrompt = settingsRepository.assistantSystemPrompt
                if (evalPrompt.isNotEmpty()) {
                    settingsRepository.assistantSystemPrompt = evalPrompt
                }
                val result = lifeLogApi.chatWithMeta(url, evalQuery, sessionId = "adb_eval")
                if (evalPrompt.isNotEmpty()) {
                    settingsRepository.assistantSystemPrompt = originalPrompt
                }
                result
                    .onSuccess { chat ->
                        Log.i(
                            "LifeLogEval",
                            "query=${evalQuery.take(160)} model=${chat.model} mode=${chat.mode} reply=${chat.reply.take(500)}"
                        )
                        Toast.makeText(this@MainActivity, "Eval complete (see logcat: LifeLogEval)", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { err ->
                        Log.w("LifeLogEval", "eval failed: ${err.message}")
                        Toast.makeText(this@MainActivity, "Eval failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }

        val place = intent?.getStringExtra("location_prompt_test")?.trim().orEmpty()
        if (place.isNotEmpty()) {
            val svc = Intent(this, com.lifelog.phone.service.LifeLogService::class.java).apply {
                action = "com.lifelog.phone.action.LOCATION_TEST_PROMPT"
                putExtra("extra_location_place", place)
            }
            startForegroundService(svc)
            Toast.makeText(this, "Location prompt test sent: $place", Toast.LENGTH_SHORT).show()
        }

        val meal = intent?.getStringExtra("meal_prompt_test")?.trim().orEmpty()
        if (meal.isNotEmpty()) {
            val svc = Intent(this, com.lifelog.phone.service.LifeLogService::class.java).apply {
                action = "com.lifelog.phone.action.MEAL_TEST_PROMPT"
                putExtra("extra_meal_key", meal)
            }
            startForegroundService(svc)
            Toast.makeText(this, "Meal prompt test sent: $meal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNotificationChatSeed(intent: Intent?) {
        val prompt = intent?.getStringExtra("chat_seed_assistant")?.trim().orEmpty()
        if (prompt.isNotEmpty()) {
            openChatRequest.value = true
            chatViewModel.seedAssistantPrompt(prompt, dedupeWindowMs = 120_000L)
        }
    }

    private fun enrollSpeakerFromLogs(speaker: String) {
        val target = speaker.trim()
        if (target.isEmpty()) {
            Toast.makeText(this, "Enter speaker name first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Recording 10s sample for $target...", Toast.LENGTH_SHORT).show()
        pendingVoiceCallback = { audio ->
            lifecycleScope.launch {
                if (audio.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No audio captured. Try again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val quality = ecapaEmbeddingEngine.assessWavQuality(audio)
                if (!quality.isUsable) {
                    Toast.makeText(
                        this@MainActivity,
                        "Enrollment quality too low (${quality.reason}). Try a quieter room and speak clearly.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val embedding = ecapaEmbeddingEngine.embedFromWavBytes(audio)
                if (embedding == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Enrollment failed: no usable voice embedding",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val slot = settingsRepository.cachedActiveSpeakerSlot.trim().uppercase()
                val clusterId = if (slot.matches(Regex("S[0-9]+"))) {
                    "slot_${slot.lowercase()}"
                } else {
                    "profile_${target.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
                }
                val current = speakerProfileDao.getByClusterId(clusterId)
                val existingVec = current?.embedding?.let { ecapaEmbeddingEngine.parse(it) }
                val blended = ecapaEmbeddingEngine.blendEmbeddings(existingVec, embedding, current?.sampleCount ?: 0)
                speakerProfileDao.upsert(
                    SpeakerProfileEntity(
                        clusterId = clusterId,
                        displayName = target,
                        embedding = ecapaEmbeddingEngine.serialize(blended),
                        sampleCount = (current?.sampleCount ?: 0) + 1,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                )
                Toast.makeText(this@MainActivity, "Enrolled $target locally", Toast.LENGTH_SHORT).show()
            }
        }
        requestAudioPermission(durationMs = 10000L)
    }

    private fun startRecording() {
        val callback = pendingVoiceCallback ?: return
        pendingVoiceCallback = null
        val duration = pendingRecordDurationMs
        lifecycleScope.launch {
            val audioData = audioRecorder.recordAudio(duration)
            callback(audioData)
        }
    }

    private fun startSingleShotVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (lifeLogApi.isWhisperDownloaded()) {
            // Fully on-device path: record 8 s → Whisper → ViewModel sends to LLM
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "Listening…", Toast.LENGTH_SHORT).show()
                val wav = audioRecorder.recordAudio(8_000L)
                chatViewModel.stopRecording()
                if (wav.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No audio captured", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val text = lifeLogApi.transcribeOnDevice(wav)
                if (text.isBlank()) {
                    Toast.makeText(this@MainActivity, "No speech detected", Toast.LENGTH_SHORT).show()
                } else {
                    chatViewModel.sendMessage(singleShotBaseUrl, text, voice = true)
                }
            }
        } else {
            // Fall back to Android SpeechRecognizer when Whisper model is not yet downloaded
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                chatViewModel.stopRecording()
                Toast.makeText(this, "Speech recognition unavailable — download Whisper model in Settings", Toast.LENGTH_LONG).show()
                return
            }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }.orEmpty().trim()
                    recognizer.destroy()
                    chatViewModel.stopRecording()
                    if (text.isNotBlank()) {
                        chatViewModel.sendMessage(singleShotBaseUrl, text, voice = true)
                    } else {
                        Toast.makeText(this@MainActivity, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(error: Int) {
                    recognizer.destroy()
                    chatViewModel.stopRecording()
                    Toast.makeText(this@MainActivity, "Voice error — download Whisper model in Settings for offline STT", Toast.LENGTH_LONG).show()
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                recognizer.destroy()
                chatViewModel.stopRecording()
                Toast.makeText(this, "Could not start voice: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startConversationMode() {
        // Conversation mode uses SpeechRecognizer; pause wake service to avoid mic contention.
        startService(Intent(this, com.lifelog.phone.service.LifeLogService::class.java).apply {
            action = com.lifelog.phone.service.LifeLogService.ACTION_TRANSCRIPTION_STOP
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onConversationError?.invoke("Microphone permission required")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onConversationError?.invoke("Speech recognizer unavailable")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        handleSpeech(partialResults, partial = true)
                    }

                    override fun onResults(results: Bundle?) {
                        handleSpeech(results, partial = false)
                    }

                    override fun onError(error: Int) {
                        if (!conversationEnabled || conversationInFlight) return
                        val isNoSpeechError =
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_CLIENT
                        if (isNoSpeechError) {
                            conversationNoSpeechErrors += 1
                            if (conversationNoSpeechErrors >= 2) {
                                conversationPausedForSilence = true
                                onConversationError?.invoke("Paused after silence. Tap Stop, then Conversation to continue.")
                                return
                            }
                        } else {
                            conversationNoSpeechErrors = 0
                        }
                        val delayMs = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> 1400L
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1500L
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                            SpeechRecognizer.ERROR_CLIENT -> 600L
                            else -> 1800L
                        }
                        onConversationError?.invoke("Listening...")
                        mainHandler.postDelayed({
                            if (conversationEnabled && !conversationInFlight && !conversationPausedForSilence) {
                                startListeningInternal()
                            }
                        }, delayMs)
                    }
                })
            }
        }
        conversationEnabled = true
        conversationInFlight = false
        conversationNoSpeechErrors = 0
        conversationPausedForSilence = false
        startListeningInternal()
    }

    private fun stopConversationMode() {
        conversationEnabled = false
        conversationInFlight = false
        conversationNoSpeechErrors = 0
        conversationPausedForSilence = false
        speechRecognizer?.cancel()
        conversationTts?.stop()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, com.lifelog.phone.service.LifeLogService::class.java).apply {
                action = com.lifelog.phone.service.LifeLogService.ACTION_TRANSCRIPTION_START
            })
        }
    }

    private fun startListeningInternal() {
        val recognizer = speechRecognizer ?: return
        if (!conversationEnabled || conversationInFlight || conversationPausedForSilence) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            onConversationError?.invoke("Listening...")
            mainHandler.postDelayed({ if (conversationEnabled && !conversationInFlight) startListeningInternal() }, 1200L)
        }
    }

    private fun handleSpeech(bundle: Bundle?, partial: Boolean) {
        if (!conversationEnabled) return
        val items = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return
        if (items.isEmpty()) return
        val best = items.first()
        if (best.isNotBlank()) {
            conversationNoSpeechErrors = 0
            conversationPausedForSilence = false
        }
        onConversationPartial?.invoke(best)
        if (!partial && best.isNotBlank()) {
            emitConversationFinal(best)
        }
    }

    private fun emitConversationFinal(text: String) {
        if (!conversationEnabled || conversationInFlight) return
        conversationInFlight = true
        conversationNoSpeechErrors = 0
        conversationPausedForSilence = false
        speechRecognizer?.cancel()
        onConversationFinal?.invoke(text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAdbProvisioning(intent)
        handleAdbTestTriggers(intent)
        handleNotificationChatSeed(intent)
        chatViewModel.loadMessages()
        intent.getStringExtra("extra_speaker_review")?.takeIf { it.isNotBlank() }?.let {
            speakerReviewTempId.value = it
        }
    }

    private fun applyAdbProvisioning(intent: Intent?) {
        val adbBaseUrl = intent?.getStringExtra("base_url")?.trim()
        val adbToken = intent?.getStringExtra("token")?.trim()
        if (!adbBaseUrl.isNullOrBlank()) {
            settingsRepository.lifeLogSyncUrl = adbBaseUrl
            Toast.makeText(this, "LifeLog server updated", Toast.LENGTH_SHORT).show()
        }
        if (!adbToken.isNullOrBlank()) {
            settingsRepository.lifeLogSyncToken = adbToken
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        wakeStateReceiver = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        conversationTts?.stop()
        conversationTts?.shutdown()
        conversationTts = null
    }

    override fun onResume() {
        super.onResume()
        chatViewModel.loadMessages()
    }

    override fun onPause() {
        super.onPause()
        if (conversationEnabled) {
            stopConversationMode()
        }
    }

    private fun speakConversationReply(text: String) {
        val t = ttsFriendlyText(text)
        if (t.isEmpty()) {
            finishConversationTurn()
            return
        }
        if (!conversationEnabled) {
            conversationInFlight = false
            return
        }
        if (!conversationTtsReady || conversationTts == null) {
            finishConversationTurn()
            return
        }
        val rc = conversationTts?.speak(
            t,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lifelog_conversation_reply"
        )
        if (rc == TextToSpeech.ERROR) {
            finishConversationTurn()
        }
    }

    private fun finishConversationTurn() {
        conversationInFlight = false
        if (conversationEnabled) {
            startListeningInternal()
        }
    }

    private fun ttsFriendlyText(input: String, maxChars: Int = 1200): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxChars) return text
        val clipped = text.substring(0, maxChars)
        val cut = maxOf(clipped.lastIndexOf('.'), clipped.lastIndexOf('!'), clipped.lastIndexOf('?'))
        return if (cut >= maxChars / 2) clipped.substring(0, cut + 1).trim() else clipped.trim()
    }
}
