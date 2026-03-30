package com.lifelog.phone.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.ui.theme.AccentBlue
import com.lifelog.phone.ui.theme.DarkBackground
import com.lifelog.phone.ui.theme.DarkSurface
import kotlinx.coroutines.launch

@Composable
fun PreLoginGateScreen(
    baseUrl: String,
    api: LifeLogApi,
    googleSignInService: GoogleSignInService,
    onGoogleSignInClick: () -> Unit,
    onProceedToApp: () -> Unit
) {
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingServer by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val signedInAccount = googleSignInService.getSignedInAccount()

    LaunchedEffect(baseUrl) {
        isCheckingServer = true
        serverReachable = checkServerReachability(api)
        isCheckingServer = false
        // Don't auto-proceed — always require explicit user action
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "LifeLog",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = AccentBlue,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Server status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Server Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                when {
                    isCheckingServer -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentBlue)
                        Spacer(Modifier.height(8.dp))
                        Text("Checking...", color = Color.White)
                    }
                    serverReachable == true -> {
                        Icon(Icons.Default.CheckCircle, "Connected", tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Connected", color = Color(0xFF4CAF50))
                    }
                    serverReachable == false -> {
                        Icon(Icons.Default.Error, "Unreachable", tint = Color(0xFFE91E63), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Unreachable", color = Color(0xFFE91E63))
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            coroutineScope.launch {
                                isCheckingServer = true
                                serverReachable = checkServerReachability(api)
                                isCheckingServer = false
                            }
                        }) {
                            Icon(Icons.Default.Refresh, "Retry", tint = AccentBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Retry", color = AccentBlue)
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onProceedToApp) {
                            Text("Continue anyway", color = Color.Gray)
                        }
                    }
                    else -> Text("Checking...", color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Auth card — only shown when server is reachable
        if (serverReachable == true) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (signedInAccount != null) {
                        Icon(Icons.Default.CheckCircle, "Signed in", tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Signed in as ${signedInAccount.email ?: signedInAccount.displayName ?: "Google"}", color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onProceedToApp, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                            Text("Continue to App")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onGoogleSignInClick) {
                            Text("Switch account", color = AccentBlue)
                        }
                    } else {
                        Text("Sign in to continue", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
                        Button(onClick = onGoogleSignInClick, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                            Text("Sign in with Google")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun checkServerReachability(api: LifeLogApi): Boolean {
    return try {
        // Always use the latest cached URL from settings, not the snapshot
        api.health(api.settingsRepository.cachedBaseUrl).getOrDefault(false)
    } catch (e: Exception) {
        false
    }
}
