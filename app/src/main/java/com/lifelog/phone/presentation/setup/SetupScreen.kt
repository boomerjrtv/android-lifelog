package com.lifelog.phone.presentation.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.ui.theme.*

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onSetupComplete: (openEnrollAfterSetup: Boolean) -> Unit
) {
    val baseUrl by viewModel.baseUrl.collectAsState()
    val token by viewModel.token.collectAsState()
    val status by viewModel.status.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var openEnrollAfterSetup by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LifeLog",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Connect to your server",
                    fontSize = 14.sp,
                    color = TextSecondary
                )

                Spacer(Modifier.height(32.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { viewModel.updateBaseUrl(it) },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://api.example.com", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = { viewModel.updateToken(it) },
                    label = { Text("Auth Token") },
                    placeholder = { Text("Optional", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = { viewModel.connect { onSetupComplete(openEnrollAfterSetup) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !isLoading && baseUrl.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = TextPrimary,
                        disabledContainerColor = AccentBlue.copy(alpha = 0.3f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = openEnrollAfterSetup,
                        onCheckedChange = { openEnrollAfterSetup = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBlue,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Open voice enrollment after connect",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    val statusColor = when {
                        status.startsWith("Error") || status.startsWith("Connection failed") -> AccentRed
                        status == "Connected!" -> AccentGreen
                        else -> AccentOrange
                    }
                    Text(text = status, color = statusColor, fontSize = 14.sp)
                }
            }
        }
    }
}
