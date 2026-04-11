package com.lifelog.phone.presentation.dashboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.phone.data.Fact
import com.lifelog.phone.data.remote.CalendarEventItem
import com.lifelog.phone.data.remote.PhoneLogEvent
import com.lifelog.phone.data.remote.RoutineItem
import com.lifelog.phone.ui.theme.*
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

// Data classes and helper functions first to ensure they are available
data class ReplayItem(
    val ts: String,
    val source: String,
    val title: String,
    val detail: String
)

private fun todayDayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun yesterdayDayKey(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_MONTH, -1)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
}

private fun matchesReplayDayFilter(day: String, filter: String): Boolean {
    val cleanDay = day.take(10)
    if (!cleanDay.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
    return when (filter.trim().lowercase()) {
        "today" -> cleanDay == todayDayKey()
        "yesterday" -> cleanDay == yesterdayDayKey()
        "all" -> true
        else -> cleanDay == todayDayKey()
    }
}

private fun buildReplayItems(logs: List<PhoneLogEvent>, calendar: List<CalendarEventItem>, filter: String): List<ReplayItem> {
    val out = mutableListOf<ReplayItem>()
    val filteredLogs = logs.filter { log ->
        matchesReplayDayFilter(log.ts, filter) && (log.kind.lowercase() == "transcript" || log.kind.lowercase() == "note")
    }
    for (log in filteredLogs) {
        out.add(ReplayItem(ts = log.ts, source = "Log", title = prettyKind(log.kind), detail = formatLogForViewer(log)))
    }
    val calItems = calendar.filter { event ->
        matchesReplayDayFilter(event.startTs, filter)
    }
    for (event in calItems) {
        val detail = if (event.isAllDay) "All day" else "${compactTs(event.startTs)} - ${compactTs(event.endTs)}"
        out.add(ReplayItem(ts = event.startTs, source = "Calendar", title = event.title.ifBlank { "Event" }, detail = detail))
    }
    return out.sortedBy { it.ts }
}

private fun prettyKind(kind: String): String {
    val raw = kind.trim().replace('_', ' ').replace('-', ' ')
    if (raw.isBlank()) return "Event"
    return raw.split(" ").joinToString(" ") { part -> if (part.isBlank()) part else part.replaceFirstChar { it.uppercase() } }
}

private fun compactTs(ts: String): String {
    val v = ts.trim().replace('T', ' ')
    return if (v.length >= 16) v.substring(0, 16) else v
}

private fun normalizeTsForSort(ts: String): String {
    val v = ts.trim().replace('T', ' ')
    return when { v.length >= 19 -> v.substring(0, 19); v.length >= 16 -> "${v.substring(0, 16)}:00"; else -> v }
}

private fun replayTimeLabel(ts: String): String {
    val v = normalizeTsForSort(ts); return if (v.length >= 16) v.substring(11, 16) else compactTs(v)
}

private fun filterCalendarByRange(items: List<CalendarEventItem>, filterKey: String): List<CalendarEventItem> {
    val key = filterKey.trim().lowercase(); if (key == "all") return items
    val todayCal = Calendar.getInstance(); val startCal = (todayCal.clone() as Calendar); val endCal = (todayCal.clone() as Calendar)
    when (key) { "today" -> {}; "this_week" -> { startCal.firstDayOfWeek = Calendar.MONDAY; while (startCal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) { startCal.add(Calendar.DAY_OF_MONTH, -1) }; endCal.time = startCal.time; endCal.add(Calendar.DAY_OF_MONTH, 6) }; "next_7_days" -> { endCal.add(Calendar.DAY_OF_MONTH, 6) }; else -> return items }
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US); val startDay = fmt.format(Date(startCal.timeInMillis)); val endDay = fmt.format(Date(endCal.timeInMillis))
    return items.filter { val v = it.startTs.trim().replace('T', ' '); if (v.length >= 10) { val day = v.substring(0, 10); day >= startDay && day <= endDay } else false }
}

private fun formatLogForViewer(item: PhoneLogEvent): String {
    val kind = item.kind.trim().lowercase(); val text = item.text.trim().ifBlank { return "(no details)" }
    return when (kind) { "battery" -> text.replace("Battery:", "Battery").replace("  ", " ").trim(); "wifi", "network" -> text.replace("Network:", "Network").replace("wifi=", "Wi-Fi ").replace(" ip=", " | IP ").trim(); "app_usage" -> text.replace("App usage:", "App").replace("package=", "").replace("duration_ms=", "for ").trim(); "browser_history" -> text.replace("Browsed:", "Visited").trim(); "location" -> text.replace("Location:", "Location").replace("lat=", "lat ").replace("lon=", "lon ").trim(); else -> text }
}

@Composable
fun DashboardScreen(
    baseUrl: String,
    viewModel: DashboardViewModel,
    onEnrollSpeaker: (String) -> Unit = {}
) {
    val isLocalOnly = baseUrl.isBlank()
    val facts by viewModel.facts.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val logTypeOptions by viewModel.logTypeOptions.collectAsState()
    val selectedLogType by viewModel.selectedLogType.collectAsState()
    val speakerOptions by viewModel.speakerOptions.collectAsState()
    val calendarEvents by viewModel.calendarEvents.collectAsState()
    val routines by viewModel.routines.collectAsState()
    val qaItems by viewModel.qaItems.collectAsState()
    val isLoadingFacts by viewModel.isLoadingFacts.collectAsState()
    val isLoadingLogs by viewModel.isLoadingLogs.collectAsState()
    val isLoadingCalendar by viewModel.isLoadingCalendar.collectAsState()
    val isLoadingRoutines by viewModel.isLoadingRoutines.collectAsState()
    val isLoadingQuestions by viewModel.isLoadingQuestions.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(error) {
        if (error.isNotEmpty()) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // Rearrange tabs: Quest first, then Facts, Logs, Daily, Events, Replay
    var selectedTab by remember(isLocalOnly) { mutableIntStateOf(if (isLocalOnly) 2 else 0) }
    var query by remember { mutableStateOf("") }
    var replayDayFilter by remember { mutableStateOf("today") }

    var showFactEditor by remember { mutableStateOf(false) }
    var factEditId by remember { mutableStateOf<Long?>(null) }
    var factText by remember { mutableStateOf("") }
    var factCategory by remember { mutableStateOf("general") }

    var showLogEditor by remember { mutableStateOf(false) }
    var logEditId by remember { mutableStateOf<Long?>(null) }
    var logKind by remember { mutableStateOf("note") }
    var logText by remember { mutableStateOf("") }

    var showTranscriptEditor by remember { mutableStateOf(false) }
    var transcriptEditId by remember { mutableStateOf(0L) }
    var transcriptText by remember { mutableStateOf("") }
    var transcriptSourceType by remember { mutableStateOf("unknown") }
    var transcriptSpeakerId by remember { mutableStateOf("") }
    var transcriptSpeakerConfidence by remember { mutableStateOf("0.0") }
    var transcriptTags by remember { mutableStateOf("") }
    var transcriptImportance by remember { mutableStateOf("0.0") }
    var transcriptMediaLikelihood by remember { mutableStateOf("0.0") }
    var transcriptDialogDensity by remember { mutableStateOf("0.0") }

    var showCalendarEditor by remember { mutableStateOf(false) }
    var calendarEditId by remember { mutableStateOf<Long?>(null) }
    var calendarTitle by remember { mutableStateOf("") }
    var calendarStartTs by remember { mutableStateOf("") }
    var calendarEndTs by remember { mutableStateOf("") }
    var calendarTimezone by remember { mutableStateOf("") }
    var calendarRecurrence by remember { mutableStateOf("") }
    var calendarLocation by remember { mutableStateOf("") }
    var calendarNotes by remember { mutableStateOf("") }
    var calendarAllDay by remember { mutableStateOf(false) }
    var calendarSyncGoogle by remember { mutableStateOf(false) }
    var calendarTimeFilter by remember { mutableStateOf("all") }

    var showRoutineEditor by remember { mutableStateOf(false) }
    var routineEditId by remember { mutableStateOf<Long?>(null) }
    var routineTitle by remember { mutableStateOf("") }
    var routineKind by remember { mutableStateOf("custom") }
    var routineAnchorKey by remember { mutableStateOf("") }
    var routineHourBucket by remember { mutableStateOf("") }
    var routineWeekdays by remember { mutableStateOf("") }
    var routineNote by remember { mutableStateOf("") }
    var routineConfidence by remember { mutableStateOf("0.5") }
    var routineActive by remember { mutableStateOf(true) }
    var routineSource by remember { mutableStateOf("manual") }
    var routineOccurrences by remember { mutableStateOf("0") }
    var routineFirstSeenTs by remember { mutableStateOf("") }
    var routineLastSeenTs by remember { mutableStateOf("") }

    val filteredCalendarEvents = filterCalendarByRange(calendarEvents, calendarTimeFilter)
    val replayItems = remember(logs, calendarEvents, replayDayFilter) {
        buildReplayItems(logs, calendarEvents, replayDayFilter)
    }

    LaunchedEffect(baseUrl) {
        viewModel.loadQuestions(baseUrl)
        viewModel.loadFacts(baseUrl)
        viewModel.loadLogs(baseUrl)
        viewModel.loadCalendar(baseUrl)
        viewModel.loadRoutines(baseUrl)
    }

    if (showFactEditor) {
        AlertDialog(
            onDismissRequest = { showFactEditor = false },
            title = { Text(if (factEditId == null) "Add Fact" else "Edit Fact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = factText, onValueChange = { factText = it }, label = { Text("Fact") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = factCategory, onValueChange = { factCategory = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { viewModel.upsertFact(id = factEditId, text = factText, category = factCategory.ifBlank { "general" }); showFactEditor = false }, enabled = factText.trim().isNotEmpty()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showFactEditor = false }) { Text("Cancel") } }
        )
    }

    if (showLogEditor) {
        AlertDialog(
            onDismissRequest = { showLogEditor = false },
            title = { Text(if (logEditId == null) "Add Log" else "Edit Log") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = logKind, onValueChange = { logKind = it }, label = { Text("Kind") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = logText, onValueChange = { logText = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { viewModel.upsertLog(id = logEditId, kind = logKind.ifBlank { "note" }, text = logText); showLogEditor = false }, enabled = logText.trim().isNotEmpty()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showLogEditor = false }) { Text("Cancel") } }
        )
    }

    if (showTranscriptEditor) {
        AlertDialog(
            onDismissRequest = { showTranscriptEditor = false },
            title = { Text("Edit Transcript") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = transcriptText, onValueChange = { transcriptText = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = transcriptSpeakerId, onValueChange = { transcriptSpeakerId = it }, label = { Text("Speaker") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (speakerOptions.isNotEmpty()) {
                        Text("Speaker quick picks", color = TextSecondary, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(speakerOptions.take(18)) { option ->
                                FilterChip(selected = transcriptSpeakerId.trim().equals(option, ignoreCase = true), onClick = { transcriptSpeakerId = option }, label = { Text(option) })
                            }
                        }
                    }
                    OutlinedTextField(value = transcriptTags, onValueChange = { transcriptTags = it }, label = { Text("Tags") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.upsertTranscript(id = transcriptEditId, text = transcriptText, sourceType = transcriptSourceType, speakerId = transcriptSpeakerId, speakerConfidence = transcriptSpeakerConfidence.toDoubleOrNull() ?: 0.0, tags = transcriptTags, importance = transcriptImportance.toDoubleOrNull() ?: 0.0, mediaLikelihood = transcriptMediaLikelihood.toDoubleOrNull() ?: 0.0, dialogDensity = transcriptDialogDensity.toDoubleOrNull() ?: 0.0)
                    showTranscriptEditor = false
                }, enabled = transcriptText.trim().isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showTranscriptEditor = false }) { Text("Cancel") } }
        )
    }

    if (showCalendarEditor) {
        AlertDialog(
            onDismissRequest = { showCalendarEditor = false },
            title = { Text(if (calendarEditId == null) "Add Event" else "Edit Event") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = calendarTitle, onValueChange = { calendarTitle = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = calendarStartTs, onValueChange = { calendarStartTs = it }, label = { Text("Start (YYYY-MM-DD HH:MM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = calendarEndTs, onValueChange = { calendarEndTs = it }, label = { Text("End (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = calendarLocation, onValueChange = { calendarLocation = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.upsertCalendarEvent(id = calendarEditId, title = calendarTitle, startTs = calendarStartTs, endTs = calendarEndTs.ifBlank { calendarStartTs }, timezone = calendarTimezone, recurrenceRule = calendarRecurrence, location = calendarLocation, notes = calendarNotes, isAllDay = calendarAllDay, source = "local", status = "confirmed", syncGoogle = calendarSyncGoogle)
                    showCalendarEditor = false
                }, enabled = calendarTitle.trim().isNotEmpty() && calendarStartTs.trim().isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCalendarEditor = false }) { Text("Cancel") } }
        )
    }

    if (showRoutineEditor) {
        AlertDialog(
            onDismissRequest = { showRoutineEditor = false },
            title = { Text(if (routineEditId == null) "Add Routine" else "Edit Routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = routineTitle, onValueChange = { routineTitle = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = routineNote, onValueChange = { routineNote = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.upsertRoutine(id = routineEditId, title = routineTitle, kind = routineKind, anchorKey = routineAnchorKey, hourBucket = routineHourBucket.toIntOrNull() ?: -1, weekdays = routineWeekdays, note = routineNote, confidence = routineConfidence.toDoubleOrNull() ?: 0.5, active = routineActive, source = routineSource, occurrences = routineOccurrences.toIntOrNull() ?: 0, firstSeenTs = routineFirstSeenTs, lastSeenTs = routineLastSeenTs)
                    showRoutineEditor = false
                }, enabled = routineTitle.trim().isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRoutineEditor = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // Branded Header
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "LifeLog", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = AccentBlue)
            Spacer(Modifier.weight(1f))
            Text(text = "DASHBOARD", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(DarkSurfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp))
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkBackground,
            contentColor = AccentBlue,
            divider = { HorizontalDivider(color = DarkBorder) },
            indicator = { tabPositions -> TabRowDefaults.SecondaryIndicator(modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]), color = AccentBlue) },
            edgePadding = 0.dp
        ) {
            // New order: Quest, Facts, Logs, Daily, Events, Replay
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; viewModel.loadQuestions(baseUrl) }, text = { Text("Questions", fontSize = 11.sp) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; viewModel.loadFacts(baseUrl) }, text = { Text("Facts", fontSize = 12.sp) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2; viewModel.loadLogs(baseUrl) }, text = { Text("Logs", fontSize = 12.sp) })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3; viewModel.loadRoutines(baseUrl) }, text = { Text("Daily", fontSize = 12.sp) })
            Tab(selected = selectedTab == 4, onClick = { selectedTab = 4; viewModel.loadCalendar(baseUrl) }, text = { Text("Events", fontSize = 12.sp) })
            Tab(selected = selectedTab == 5, onClick = { selectedTab = 5 }, text = { Text("Replay", fontSize = 12.sp) })
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    when(selectedTab) {
                        1 -> viewModel.searchFacts(it)
                        2 -> viewModel.searchLogs(it)
                        3 -> viewModel.searchRoutines(it)
                        4 -> viewModel.searchCalendar(it)
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search ${when(selectedTab) { 0 -> "questions"; 1 -> "facts"; 2 -> "logs"; 3 -> "routines"; 4 -> "calendar"; else -> "" }}") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, unfocusedBorderColor = DarkBorder, focusedContainerColor = DarkSurfaceVariant, unfocusedContainerColor = DarkSurfaceVariant)
            )
            IconButton(onClick = { when(selectedTab) { 0 -> viewModel.loadQuestions(baseUrl); 1 -> viewModel.loadFacts(baseUrl); 2 -> viewModel.loadLogs(baseUrl); 3 -> viewModel.loadRoutines(baseUrl); 4 -> viewModel.loadCalendar(baseUrl); 5 -> { viewModel.loadLogs(baseUrl); viewModel.loadCalendar(baseUrl) } } }) { Icon(Icons.Default.Refresh, "Refresh", tint = AccentBlue) }
            if (selectedTab in 1..4) {
                IconButton(onClick = {
                    when(selectedTab) {
                        1 -> { factEditId = null; factText = ""; factCategory = "general"; showFactEditor = true }
                        2 -> { logEditId = null; logKind = "note"; logText = ""; showLogEditor = true }
                        3 -> { routineEditId = null; routineTitle = ""; routineNote = ""; showRoutineEditor = true }
                        4 -> { calendarEditId = null; calendarTitle = ""; calendarStartTs = ""; calendarEndTs = ""; showCalendarEditor = true }
                    }
                }) { Icon(Icons.Default.Add, "Add", tint = AccentBlue) }
            }
        }

        if (isLocalOnly) {
            Text(
                text = "App-only mode: Dashboard is using local phone data. Questions and remote audio are unavailable.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        if (selectedTab == 2) {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logTypeOptions) { opt -> FilterChip(selected = selectedLogType == opt.key, onClick = { viewModel.setLogTypeFilter(opt.key) }, label = { Text("${opt.label} (${opt.count})") }) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (selectedTab == 5) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = replayDayFilter == "today", onClick = { replayDayFilter = "today" }, label = { Text("Today") })
                FilterChip(selected = replayDayFilter == "yesterday", onClick = { replayDayFilter = "yesterday" }, label = { Text("Yesterday") })
                FilterChip(selected = replayDayFilter == "all", onClick = { replayDayFilter = "all" }, label = { Text("All") })
                Spacer(Modifier.weight(1f)); Text(text = "${replayItems.size} items", color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if ((selectedTab == 0 && isLoadingQuestions) || (selectedTab == 1 && isLoadingFacts) || (selectedTab == 2 && isLoadingLogs) || (selectedTab == 3 && isLoadingRoutines) || (selectedTab == 4 && isLoadingCalendar)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = AccentBlue)
            } else {
                when(selectedTab) {
                    0 -> if (isLocalOnly) {
                        EmptyDashboardMessage("Questions need a remote LifeLog server. Local logs, routines, events, and replay still work in the app.")
                    } else {
                        QuestionsList(items = qaItems, onAnswer = { qItem, ans -> viewModel.answerQuestion(baseUrl, qItem, ans); Toast.makeText(context, "Submitted!", Toast.LENGTH_SHORT).show() }, onPlayAudio = { stem, ts -> viewModel.playAudio(stem, ts) })
                    }
                    1 -> FactsList(items = facts, onEdit = { fact -> factEditId = fact.id; factText = fact.text; factCategory = "general"; showFactEditor = true }, onDelete = { fact -> viewModel.deleteFact(fact.id) })
                    2 -> LogsList(items = logs, selectedType = selectedLogType, onEdit = { row -> if (row.kind.lowercase() == "transcript") { transcriptEditId = if (row.transcriptId > 0L) row.transcriptId else kotlin.math.abs(row.id); transcriptText = row.text; transcriptSpeakerId = row.speakerId; transcriptTags = row.tags; showTranscriptEditor = true } else { logEditId = row.id; logKind = row.kind; logText = row.text; showLogEditor = true } }, onDelete = { row -> viewModel.deleteLog(row.id) }, onPlayAudio = { stem, ts -> viewModel.playAudio(stem, ts) })
                    3 -> RoutinesList(items = routines, onEdit = { item -> routineEditId = item.id; routineTitle = item.title; routineNote = item.note; showRoutineEditor = true }, onToggleActive = { item -> viewModel.upsertRoutine(id = item.id, title = item.title, kind = item.kind, anchorKey = item.anchorKey, hourBucket = item.hourBucket, weekdays = item.weekdays, note = item.note, confidence = item.confidence, active = !item.active, source = item.source, occurrences = item.occurrences, firstSeenTs = item.firstSeenTs, lastSeenTs = item.lastSeenTs) }, onDelete = { item -> viewModel.deleteRoutine(item.id) })
                    4 -> CalendarList(items = filteredCalendarEvents, onEdit = { item -> calendarEditId = item.id; calendarTitle = item.title; calendarStartTs = item.startTs; calendarEndTs = item.endTs; showCalendarEditor = true }, onDelete = { item -> viewModel.deleteCalendarEvent(id = item.id, syncGoogle = item.source.lowercase() == "google") })
                    5 -> ReplayList(items = replayItems)
                }
            }
        }
    }
}

@Composable
private fun FactsList(items: List<Fact>, onEdit: (Fact) -> Unit, onDelete: (Fact) -> Unit) {
    if (items.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No facts yet", color = TextMuted) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.text, color = TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onEdit(item) }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)); Text("Edit") }
                        TextButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp)); Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDashboardMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Text(
                text = text,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun LogsList(items: List<PhoneLogEvent>, selectedType: String, onEdit: (PhoneLogEvent) -> Unit, onDelete: (PhoneLogEvent) -> Unit, onPlayAudio: (String, String) -> Unit) {
    if (items.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No logs", color = TextMuted) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val isTranscript = item.kind.lowercase() == "transcript"
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(prettyKind(item.kind), color = AccentBlue, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(8.dp)); Text(compactTs(item.ts), color = TextSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp)); Text(formatLogForViewer(item), color = TextPrimary, fontSize = 14.sp)
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onEdit(item) }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)); Text("Edit") }
                        if (isTranscript && item.source.isNotBlank()) {
                            TextButton(onClick = { onPlayAudio(item.source, item.ts) }) { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp)); Text("Play") }
                        } else if (!isTranscript) {
                            TextButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp)); Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarList(items: List<CalendarEventItem>, onEdit: (CalendarEventItem) -> Unit, onDelete: (CalendarEventItem) -> Unit) {
    var calendarDate by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    val monthEvents = remember(items, calendarDate) {
        val month = calendarDate.get(Calendar.MONTH); val year = calendarDate.get(Calendar.YEAR)
        items.filter { try { val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.startTs.take(10)); d != null && Calendar.getInstance().apply { time = d }.let { it.get(Calendar.MONTH) == month && it.get(Calendar.YEAR) == year } } catch (_: Exception) { false } }
    }
    val selectedDayEvents = remember(items, selectedDate) {
        val dayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.time)
        items.filter { it.startTs.startsWith(dayStr) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { calendarDate = (calendarDate.clone() as Calendar).apply { add(Calendar.MONTH, -1) } }) { Icon(Icons.Default.ChevronLeft, "Prev") }
            Text(text = SimpleDateFormat("MMMM yyyy", Locale.US).format(calendarDate.time), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AccentBlue)
            IconButton(onClick = { calendarDate = (calendarDate.clone() as Calendar).apply { add(Calendar.MONTH, 1) } }) { Icon(Icons.Default.ChevronRight, "Next") }
        }
        Row(modifier = Modifier.fillMaxWidth()) { listOf("S","M","T","W","T","F","S").forEach { Text(text = it, modifier = Modifier.weight(1f), color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) } }
        val daysInMonth = calendarDate.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDay = (calendarDate.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1
        Column(modifier = Modifier.padding(8.dp)) {
            for (r in 0 until 6) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val d = r * 7 + c - firstDay + 1
                        if (d in 1..daysInMonth) {
                            val isSel = selectedDate.get(Calendar.DAY_OF_MONTH) == d && selectedDate.get(Calendar.MONTH) == calendarDate.get(Calendar.MONTH) && selectedDate.get(Calendar.YEAR) == calendarDate.get(Calendar.YEAR)
                            val hasEv = monthEvents.any { try { val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.startTs.take(10)); date != null && Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_MONTH) == d } catch (_: Exception) { false } }
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(8.dp)).background(if (isSel) AccentBlue.copy(alpha = 0.2f) else Color.Transparent).clickable { selectedDate = (calendarDate.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) } }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = d.toString(), color = if (isSel) AccentBlue else TextPrimary, fontSize = 14.sp)
                                    if (hasEv) Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(if (isSel) AccentBlue else AccentOrange))
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        HorizontalDivider(color = DarkBorder)
        if (selectedDayEvents.isEmpty()) Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No events", color = TextMuted) }
        else LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(selectedDayEvents) { ev -> Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Column(modifier = Modifier.padding(12.dp)) { Text(ev.title, color = AccentBlue, fontWeight = FontWeight.Bold); Text(ev.display, color = TextPrimary, fontSize = 14.sp) } } }
        }
    }
}

@Composable
private fun RoutinesList(items: List<RoutineItem>, onEdit: (RoutineItem) -> Unit, onToggleActive: (RoutineItem) -> Unit, onDelete: (RoutineItem) -> Unit) {
    if (items.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No routines", color = TextMuted) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.title, color = AccentBlue, fontWeight = FontWeight.Bold)
                    Text(item.note, color = TextPrimary, fontSize = 14.sp)
                    Row {
                        TextButton(onClick = { onToggleActive(item) }) { Text(if (item.active) "Disable" else "Enable") }
                        TextButton(onClick = { onDelete(item) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayList(items: List<ReplayItem>) {
    if (items.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No activity found", color = TextMuted) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { item ->
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${replayTimeLabel(item.ts)} • ${item.source}", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(item.title, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(item.detail, color = TextSecondary, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun QuestionsList(items: List<com.lifelog.phone.data.remote.DataQuestionItem>, onAnswer: (com.lifelog.phone.data.remote.DataQuestionItem, String) -> Unit, onPlayAudio: (String, String) -> Unit) {
    if (items.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No questions", color = TextMuted) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { item -> QuestionItemCard(item = item, onAnswer = onAnswer, onPlayAudio = onPlayAudio) }
    }
}

@Composable
private fun QuestionItemCard(item: com.lifelog.phone.data.remote.DataQuestionItem, onAnswer: (com.lifelog.phone.data.remote.DataQuestionItem, String) -> Unit, onPlayAudio: (String, String) -> Unit) {
    val category = item.meta.optString("category", "").lowercase()
    val stem = item.meta.optString("stem", "")
    val ts = item.meta.optString("ts", "")
    val icon = when (category) { "meal" -> Icons.Default.Fastfood; "speaker" -> Icons.Default.Person; "calibration" -> Icons.Default.Bolt; else -> Icons.Default.QuestionMark }
    val iconColor = when (category) { "meal" -> AccentOrange; "speaker" -> AccentGreen; "calibration" -> AccentBlue; else -> TextSecondary }
    var isAnswered by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        if (isAnswered) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, "Done", tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Text("Got it, thanks!", color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
                    Text(item.title.ifBlank { "Question" }, color = iconColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    if (stem.isNotBlank()) {
                        IconButton(onClick = { onPlayAudio(stem, ts) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.PlayArrow, "Play", tint = iconColor)
                        }
                    }
                }
                Text(item.prompt, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp), maxLines = 3)
                if (item.context.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DarkBackground.copy(alpha = 0.5f)).padding(8.dp)) {
                        Text(item.context, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (item.options.isNotEmpty()) {
                    item.options.chunked(2).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { opt ->
                                Button(onClick = { isAnswered = true; onAnswer(item, opt) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = iconColor.copy(alpha = 0.15f), contentColor = TextPrimary)) {
                                    Text(opt, fontSize = 13.sp, maxLines = 2)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (item.type != "choice") {
                    var ans by remember { mutableStateOf("") }
                    var sub by remember { mutableStateOf(false) }
                    OutlinedTextField(value = ans, onValueChange = { ans = it }, placeholder = { Text(item.placeholder.ifBlank { "Type answer..." }, color = TextMuted) }, modifier = Modifier.fillMaxWidth(), enabled = !sub, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = iconColor, unfocusedBorderColor = DarkBorder))
                    Button(onClick = { sub = true; isAnswered = true; onAnswer(item, ans) }, enabled = ans.isNotBlank() && !sub, modifier = Modifier.align(Alignment.End).padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = iconColor)) {
                        if (sub) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary) else Text("Submit")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoubleLineField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, unfocusedBorderColor = DarkBorder))
}
