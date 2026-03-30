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
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lifelog.phone.Api
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.presentation.auth.GoogleSignInService
import com.lifelog.phone.presentation.auth.PreLoginGateScreen
import com.lifelog.phone.presentation.chat.ChatScreen
import com.lifelog.phone.presentation.chat.ChatViewModel
import com.lifelog.phone.presentation.dashboard.DashboardScreen
import com.lifelog.phone.presentation.dashboard.DashboardViewModel
import com.lifelog.phone.presentation.insights.InsightsScreen
import com.lifelog.phone.presentation.audio.AudioRecorder
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

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var googleSignInService: GoogleSignInService


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
    private var conversationEnabled = false
    private var conversationInFlight = false
    private var onConversationPartial: ((String) -> Unit)? = null
    private var onConversationFinal: ((String) -> Unit)? = null
    private var onConversationError: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var conversationTts: TextToSpeech? = null
    private var conversationTtsReady = false
    private val wakeStatusText = mutableStateOf("Starting wakeword...")
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
        val loggingIntent = Intent(this, com.lifelog.phone.service.LifeLogService::class.java)
        startForegroundService(loggingIntent)
        val wakeIntent = Intent(this, WakeWordService::class.java)
        startForegroundService(wakeIntent)
        registerWakeStateReceiver()

        setContent {
            LifeLogTheme {
                val isConfigured = settingsRepository.isConfigured
                if (isConfigured) {
                    MainApp()
                } else {
                    // Show simple setup prompt since SetupScreen is not available
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Please configure server settings")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainApp() {
        val openEnrollAfterSetup = settingsRepository.cachedOpenEnrollAfterSetup
        var selectedTab by remember { mutableIntStateOf(if (openEnrollAfterSetup) 3 else 0) }
        var showPreLoginGate by remember { mutableStateOf(true) }
        val baseUrl = settingsRepository.cachedBaseUrl
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

        // Show speaker review screen when opened from notification
        if (speakerReview != null) {
            val api = remember { com.lifelog.phone.data.remote.LifeLogApi(settingsRepository) }
            SpeakerReviewScreen(
                tempId = speakerReview,
                api = api,
                onDismiss = { speakerReviewTempId.value = null },
            )
            return
        }

        // Show pre-login gate if needed
        if (showPreLoginGate) {
            val api = remember { LifeLogApi(settingsRepository) }
            PreLoginGateScreen(
                api = api,
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
                                3 -> "Settings"
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
                                onVoiceRecord = { callback ->
                                    pendingVoiceCallback = callback
                                    requestAudioPermission(durationMs = 3000L)
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
                        val api = remember { LifeLogApi(settingsRepository) }
                        InsightsScreen(
                            api = api,
                            googleSignInService = googleSignInService,
                            onGoogleSignInClick = {
                                googleSignInLauncher.launch(googleSignInService.getSignInIntent())
                            }
                        )
                    }
                    3 -> SettingsContent()
                }
            }
        }
    }

    @Composable
    private fun SettingsContent() {
        var serverUrl by remember { mutableStateOf(settingsRepository.baseUrl) }
        var authToken by remember { mutableStateOf(settingsRepository.token) }
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
        val api = remember { com.lifelog.phone.data.remote.LifeLogApi(settingsRepository) }

        LaunchedEffect(Unit) {
            val base = settingsRepository.cachedBaseUrl.trim()
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
                label = { Text("Server URL") },
                placeholder = { Text("https://your-tunnel.trycloudflare.com", color = TextMuted) },
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
                label = { Text("Auth Token") },
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
                        settingsRepository.baseUrl = serverUrl
                        settingsRepository.token = authToken
                        testStatus = "Saved!"
                        val base = settingsRepository.cachedBaseUrl.trim()
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
            val models = listOf("Ollama", "Gemini", "Zai", "Custom")
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
                                    api.updateSetting(serverUrl.trim().trimEnd('/'), "ai_provider", selectedModel)
                                    api.updateSetting(serverUrl.trim().trimEnd('/'), "custom_config", payload.toString())
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
                                val svc = Intent(this@MainActivity, com.lifelog.phone.service.WakeWordService::class.java)
                                startForegroundService(svc)
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
                                val svc = Intent(this@MainActivity, com.lifelog.phone.service.WakeWordService::class.java)
                                stopService(svc)
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
                            settingsRepository.cachedBaseUrl.take(40),
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
        val base = settingsRepository.cachedBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            Toast.makeText(this, "Set server URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Recording 10s sample for $target...", Toast.LENGTH_SHORT).show()
        pendingVoiceCallback = { audio ->
            lifecycleScope.launch {
                if (audio.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No audio captured. Try again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val api = LifeLogApi(settingsRepository)
                val result = api.enrollSpeaker(base, target, audio)
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "Enrolled $target", Toast.LENGTH_SHORT).show()
                    dashboardViewModel.loadLogs(base)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Enroll failed: ${result.exceptionOrNull()?.message ?: "unknown"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
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

    private fun startConversationMode() {
        // Conversation mode uses SpeechRecognizer; pause wake service to avoid mic contention.
        stopService(Intent(this, com.lifelog.phone.service.WakeWordService::class.java))

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
                        val delayMs = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> 1400L
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                            SpeechRecognizer.ERROR_CLIENT -> 600L
                            else -> 1800L
                        }
                        onConversationError?.invoke("Listening...")
                        mainHandler.postDelayed({ if (conversationEnabled && !conversationInFlight) startListeningInternal() }, delayMs)
                    }
                })
            }
        }
        conversationEnabled = true
        conversationInFlight = false
        startListeningInternal()
    }

    private fun stopConversationMode() {
        conversationEnabled = false
        conversationInFlight = false
        speechRecognizer?.cancel()
        conversationTts?.stop()
        // Restore wake service after conversation mode exits.
        startForegroundService(Intent(this, com.lifelog.phone.service.WakeWordService::class.java))
    }

    private fun startListeningInternal() {
        val recognizer = speechRecognizer ?: return
        if (!conversationEnabled || conversationInFlight) return
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
        onConversationPartial?.invoke(best)
        if (!partial && best.isNotBlank()) {
            emitConversationFinal(best)
        }
    }

    private fun emitConversationFinal(text: String) {
        if (!conversationEnabled || conversationInFlight) return
        conversationInFlight = true
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
            settingsRepository.baseUrl = adbBaseUrl
            Toast.makeText(this, "LifeLog server updated", Toast.LENGTH_SHORT).show()
        }
        if (!adbToken.isNullOrBlank()) {
            settingsRepository.token = adbToken
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
