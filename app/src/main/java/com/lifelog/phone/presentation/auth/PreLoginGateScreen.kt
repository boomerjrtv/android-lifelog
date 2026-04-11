package com.lifelog.phone.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.ui.theme.AccentBlue
import com.lifelog.phone.ui.theme.DarkBackground
import com.lifelog.phone.ui.theme.DarkSurface

@Composable
fun PreLoginGateScreen(
    baseUrl: String,
    api: LifeLogApi,
    googleSignInService: GoogleSignInService,
    onGoogleSignInClick: () -> Unit,
    onProceedToApp: () -> Unit
) {
    val signedInAccount = googleSignInService.getSignedInAccount()

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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Continue into the app. This build runs locally on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                if (baseUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (signedInAccount != null) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Signed in",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Signed in as ${signedInAccount.email ?: signedInAccount.displayName ?: "Google"}",
                        color = Color.White
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onProceedToApp,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text("Continue to App")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onGoogleSignInClick) {
                        Text("Switch account", color = AccentBlue)
                    }
                } else {
                    Text(
                        "Sign in to continue",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Button(
                        onClick = onGoogleSignInClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text("Sign in with Google")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onProceedToApp) {
                        Text("Continue without sign-in", color = Color.Gray)
                    }
                }
            }
        }
    }
}
