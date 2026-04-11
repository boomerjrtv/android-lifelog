package com.lifelog.phone.presentation.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.data.remote.Insight
import com.lifelog.phone.data.remote.LifeLogApi
import com.lifelog.phone.presentation.auth.GoogleSignInService
import com.lifelog.phone.ui.theme.AccentBlue
import com.lifelog.phone.ui.theme.DarkBackground
import com.lifelog.phone.ui.theme.DarkSurfaceVariant
import com.lifelog.phone.ui.theme.TextMuted
import com.lifelog.phone.ui.theme.TextPrimary
import com.lifelog.phone.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun InsightsScreen(
    api: LifeLogApi,
    googleSignInService: GoogleSignInService,
    onGoogleSignInClick: () -> Unit
) {
    var insights by remember { mutableStateOf<Map<String, List<Insight>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val signedInAccount = googleSignInService.getSignedInAccount()

    fun loadInsights() {
        coroutineScope.launch {
            isLoading = true
            error = null
            try {
                val response = api.getInsights()
                insights = response.insights
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Failed to load insights"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(signedInAccount) {
        if (signedInAccount != null) loadInsights()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AccentBlue)
            if (signedInAccount != null) {
                IconButton(onClick = { loadInsights() }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = AccentBlue)
                }
            }
        }

        if (signedInAccount == null) {
            // Not signed in
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign in with Google to view Insights", color = TextMuted, modifier = Modifier.padding(bottom = 16.dp))
                    Button(onClick = onGoogleSignInClick, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                        Text("Sign in with Google")
                    }
                }
            }
        } else when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
            error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "Error", color = Color.Red, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadInsights() }) { Text("Retry") }
                }
            }
            insights.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No insights available", color = TextMuted)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                insights["memories"]?.takeIf { it.isNotEmpty() }?.let { items ->
                    item { InsightSection("Recent Memories", Icons.Default.Lightbulb, items) }
                }
                insights["upcoming"]?.takeIf { it.isNotEmpty() }?.let { items ->
                    item { InsightSection("Upcoming Events", Icons.Default.Event, items) }
                }
                insights["tasks"]?.takeIf { it.isNotEmpty() }?.let { items ->
                    item { InsightSection("Task Mentions", Icons.Default.Task, items) }
                }
            }
        }
    }
}

@Composable
private fun InsightSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, items: List<Insight>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AccentBlue)
        }
        items.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    item.title?.let { Text(it, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
                    item.text.takeIf { it.isNotBlank() }?.let { Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
                    item.time?.let { Text(it, color = AccentBlue, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                    item.location?.let { Text("📍 $it", color = TextMuted, fontSize = 11.sp) }
                }
            }
        }
    }
}
