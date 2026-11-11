package app.fetchflow

import android.content.Intent
import android.net.Uri
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.util.Calendar
import java.util.Date

class MainActivity : ComponentActivity() {
    private var recoveredLegacyQueue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = incomingLinks(intent)
        setContent { FetchFlowApp(incoming) }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 34 && !recoveredLegacyQueue) {
            recoveredLegacyQueue = true
            val store = DownloadStore.get(this)
            store.recoverLegacyTransfers().forEach { TransferLauncher.start(this, it) }
        }
    }
}

private enum class Destination(val label: String) { Downloads("Downloads"), Queue("Queue"), Files("Files"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FetchFlowApp(incomingLinks: List<String>) {
    val context = LocalContext.current
    val store = remember { DownloadStore.get(context) }
    var page by remember { mutableStateOf(Destination.Downloads) }
    var records by remember { mutableStateOf(store.items()) }
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DownloadItem?>(null) }
    var extractingArchive by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("fetchflow.preferences", android.content.Context.MODE_PRIVATE) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", true)) }
    var maxParts by remember { mutableIntStateOf(preferences.getInt("max_parts", 8).coerceIn(1, 16)) }
    var speedLimit by remember { mutableStateOf(preferences.getLong("speed_limit", 0L)) }
    var maxActiveDownloads by remember { mutableIntStateOf(preferences.getInt("max_active_downloads", 2).coerceIn(1, 4)) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            records = store.items()
            delay(1200)
        }
    }
    LaunchedEffect(incomingLinks) {
        if (incomingLinks.isNotEmpty()) showAdd = true
    }

    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB7C6FF), secondary = Color(0xFF82D5C8),
        background = Color(0xFF111318), surface = Color(0xFF191B22),
        surfaceVariant = Color(0xFF2B2E38), primaryContainer = Color(0xFF263B77),
        onPrimaryContainer = Color(0xFFE0E6FF)
    ) else lightColorScheme(
        primary = Color(0xFF315DCC), onPrimary = Color.White,
        secondary = Color(0xFF087E72), background = Color(0xFFF6F7FB),
        surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9ECF4),
        primaryContainer = Color(0xFFE1E8FF), onPrimaryContainer = Color(0xFF152E70)
    )

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        if (searchOpen) {
                            BasicTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                decorationBox = { inner ->
                                    if (searchText.isEmpty()) Text("Search downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    inner()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else Column {
                            Text("FetchFlow", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                            Text("Your downloads, under control", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (searchOpen) {
                                searchOpen = false
                                searchText = ""
                            } else searchOpen = true
                            page = Destination.Downloads
                        }) { Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searchOpen) "Close search" else "Search downloads") }
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.AddLink, contentDescription = "Add a link") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        val icon = when (destination) {
                            Destination.Downloads -> Icons.Default.Downloading
                            Destination.Queue -> Icons.Default.ViewAgenda
                            Destination.Files -> Icons.Default.FolderOpen
                            Destination.Settings -> Icons.Default.Tune
                        }
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { page = destination },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (page != Destination.Settings) {
                    ExtendedFloatingActionButton(
                        onClick = { showAdd = true }, icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Add link") }, containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        ) { padding ->
            AnimatedContent(page, label = "page") { destination ->
                when (destination) {
                    Destination.Downloads -> DownloadsPage(records, searchText, { selected = it }, { showAdd = true }, Modifier.padding(padding))
                    Destination.Queue -> QueuePage(records, Modifier.padding(padding), onRetry = { store.retry(it); records = store.items() }, onPause = { store.pause(it); records = store.items() }, onCancel = { store.cancel(it); records = store.items() })
                    Destination.Files -> FilesPage(
                        records.filter { it.state == "complete" },
                        onOpen = { item -> openDownload(context, store, item) },
                        onShare = { item -> shareDownload(context, store, item) },
                        onExtract = { item ->
                            scope.launch {
                                extractingArchive = item.name
                                val result = runCatching {
                                    val uri = store.openUri(item) ?: error("The downloaded file is unavailable.")
                                    withContext(Dispatchers.IO) { ZipExtractor.extract(context, uri, item.name) }
                                }
                                result.onSuccess { extracted ->
                                    android.widget.Toast.makeText(context, "Extracted ${extracted.fileCount} files to Downloads/FetchFlow/Extracted/${extracted.folderName}", android.widget.Toast.LENGTH_LONG).show()
                                }.onFailure { failure ->
                                    android.widget.Toast.makeText(context, failure.message ?: "Could not extract this archive.", android.widget.Toast.LENGTH_LONG).show()
                                }
                                extractingArchive = null
                            }
                        },
                        modifier = Modifier.padding(padding)
                    )
                    Destination.Settings -> SettingsPage(
                        wifiOnly, maxParts, speedLimit, maxActiveDownloads,
                        notificationsEnabled = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                        onManageNotifications = { openNotificationSettings(context) },
                        onWifiOnlyChange = { wifiOnly = it; preferences.edit().putBoolean("wifi_only", it).apply() },
                        onMaxPartsChange = { maxParts = it; preferences.edit().putInt("max_parts", it).apply() },
                        onSpeedLimitChange = { speedLimit = it; preferences.edit().putLong("speed_limit", it).apply(); DownloadThrottle.configure(it) },
                        onMaxActiveChange = { maxActiveDownloads = it; preferences.edit().putInt("max_active_downloads", it).apply(); DownloadConcurrency.configure(it) },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }

        if (showAdd) {
            AddDownloadDialog(
                initialUrl = incomingLinks.joinToString("\n"), wifiOnly = wifiOnly, partCount = maxParts, maxActiveDownloads = maxActiveDownloads,
                onDismiss = { showAdd = false; error = null },
                onAdd = { url, name, sha256, batch, scheduledAt ->
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        !preferences.getBoolean("notification_permission_prompted", false)
                    ) {
                        preferences.edit().putBoolean("notification_permission_prompted", true).apply()
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    runCatching {
                        if (batch) store.addBatch(url, wifiOnly, maxParts, scheduledAt)
                        else listOf(store.add(url, name, wifiOnly, maxParts, sha256, scheduledAt))
                    }
                        .onSuccess { added ->
                            records = store.items(); error = null; showAdd = false
                            if (added.size > 1) android.widget.Toast.makeText(context, if (scheduledAt > System.currentTimeMillis()) "${added.size} downloads scheduled" else "${added.size} downloads added to queue", android.widget.Toast.LENGTH_SHORT).show()
                            else if (scheduledAt > System.currentTimeMillis()) android.widget.Toast.makeText(context, "Download scheduled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { error = it.message ?: "Could not add this link." }
                }, error = error
            )
        }
        selected?.let { item ->
            DownloadDetailDialog(
                item, onDismiss = { selected = null },
                onCancel = { store.cancel(item.id); records = store.items(); selected = null },
                onRetry = { store.retry(item.id); records = store.items(); selected = null },
                onPause = { store.pause(item.id); records = store.items(); selected = null },
                onResume = { store.resume(item.id); records = store.items(); selected = null }
            )
        }
        extractingArchive?.let { name ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Extracting ZIP") },
                text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(14.dp)); Text("$name\nKeep FetchFlow open until extraction finishes.") } },
                confirmButton = {}
            )
        }
    }
}

@Composable
private fun DownloadsPage(records: List<DownloadItem>, query: String, onSelect: (DownloadItem) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val matching = records.filter { query.isBlank() || it.name.contains(query, true) || it.url.contains(query, true) }
    val active = matching.filter { it.state == "downloading" || it.state == "queued" || it.state == "scheduled" || it.state == "paused" || it.state == "failed" }
    val completed = matching.filter { it.state == "complete" }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        SummaryCard(active)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("In progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${active.size} active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (active.isEmpty() && completed.isEmpty()) EmptyState(if (query.isBlank()) "Nothing downloading" else "No matching downloads", if (query.isBlank()) "Share a link to FetchFlow or add one here." else "Try another filename or website address.", Icons.Default.Download, onAdd)
        else LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(active, key = { it.id }) { DownloadCard(it, onClick = { onSelect(it) }) }
            if (completed.isNotEmpty()) item { Text("Recently completed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
            items(completed.take(5), key = { "done-${it.id}" }) { DownloadCard(it, onClick = { onSelect(it) }) }
        }
    }
}

@Composable
private fun SummaryCard(active: List<DownloadItem>) {
    val downloading = active.count { it.state == "downloading" }
    Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f), modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (downloading == 0) "Ready when you are" else "$downloading downloading", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${active.size} active or queued", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onClick: () -> Unit) {
    val color = when (item.state) {
        "complete" -> Color(0xFF087E72)
        "failed" -> MaterialTheme.colorScheme.error
        "paused", "queued", "scheduled" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .12f), modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(statusIcon(item.state), contentDescription = null, tint = color) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Uri.parse(item.url).host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(item.state.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { if (item.state == "complete") 1f else item.progress }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (item.total > 0) "${formatBytes(item.bytes)} of ${formatBytes(item.total)}" else formatBytes(item.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (item.total > 0) "${(item.progress * 100).toInt()}%" else if (item.state == "complete") "Done" else "Details ›", style = MaterialTheme.typography.labelSmall, color = color)
            }
            if (item.reason.isNotBlank() && item.state != "complete") {
                Spacer(Modifier.height(5.dp)); Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QueuePage(records: List<DownloadItem>, modifier: Modifier = Modifier, onRetry: (Long) -> Unit, onPause: (Long) -> Unit, onCancel: (Long) -> Unit) {
    val queued = records.filter { it.state == "queued" || it.state == "scheduled" || it.state == "paused" || it.state == "failed" }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Transfer queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Waiting downloads can be paused; paused items can resume, and failed items can retry.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
        if (queued.isEmpty()) EmptyState("Queue is clear", "New links will appear here while waiting to download.", Icons.Default.CheckCircle, {})
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(queued, key = { it.id }) { item ->
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.state.replaceFirstChar { it.uppercase() } + if (item.reason.isNotBlank()) " · ${item.reason}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (item.state == "paused") TextButton(onClick = { onRetry(item.id) }) { Text("Resume") }
                            else if (item.state == "failed") TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                            else if (item.state == "scheduled") TextButton(onClick = { onPause(item.id) }) { Text("Pause") }
                            else if (item.state == "queued") TextButton(onClick = { onPause(item.id) }) { Text("Pause") }
                            TextButton(onClick = { onCancel(item.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesPage(files: List<DownloadItem>, onOpen: (DownloadItem) -> Unit, onShare: (DownloadItem) -> Unit, onExtract: (DownloadItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Your files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Saved to Downloads / FetchFlow", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        if (files.isEmpty()) EmptyState("No files yet", "Completed downloads will be ready to open and share here.", Icons.Default.FolderOpen, {})
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(files, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(formatBytes(item.total)) },
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Row {
                            if (item.name.substringAfterLast('.', "").equals("zip", ignoreCase = true)) {
                                IconButton(onClick = { onExtract(item) }) { Icon(Icons.Default.Unarchive, "Extract ZIP") }
                            }
                            IconButton(onClick = { onShare(item) }) { Icon(Icons.Default.Share, "Share file") }
                            IconButton(onClick = { onOpen(item) }) { Icon(Icons.Default.OpenInNew, "Open") }
                        }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { onOpen(item) }
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(wifiOnly: Boolean, maxParts: Int, speedLimit: Long, maxActiveDownloads: Int, notificationsEnabled: Boolean, onManageNotifications: () -> Unit, onWifiOnlyChange: (Boolean) -> Unit, onMaxPartsChange: (Int) -> Unit, onSpeedLimitChange: (Long) -> Unit, onMaxActiveChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var partsExpanded by remember { mutableStateOf(false) }
    var speedExpanded by remember { mutableStateOf(false) }
    var activeExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Simple controls for how FetchFlow uses your phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            ListItem(
                headlineContent = { Text("Download notifications") },
                supportingContent = { Text(if (notificationsEnabled) "Notifications are enabled; pause or cancel transfers from the notification shade." else "Enable notifications to see progress and control transfers outside the app.") },
                leadingContent = { Icon(if (notificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { TextButton(onClick = onManageNotifications) { Text(if (notificationsEnabled) "Settings" else "Enable") } }
            )
        }
        Card(shape = RoundedCornerShape(20.dp)) {
            Column {
                ListItem(
                    headlineContent = { Text("Unmetered network only") },
                    supportingContent = { Text("Wait for Wi-Fi or another network Android marks unmetered") },
                    leadingContent = { Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChange) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(headlineContent = { Text("Save location") }, supportingContent = { Text("Downloads / FetchFlow") }, leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Maximum connections") },
                    supportingContent = { Text("Used only when the server supports segmented downloads") },
                    leadingContent = { Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { partsExpanded = true }) { Text("$maxParts") }
                            DropdownMenu(expanded = partsExpanded, onDismissRequest = { partsExpanded = false }) {
                                listOf(1, 4, 8, 16).forEach { count ->
                                    DropdownMenuItem(text = { Text(if (count == 1) "1 connection" else "$count connections") }, onClick = { onMaxPartsChange(count); partsExpanded = false })
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Simultaneous downloads") },
                    supportingContent = { Text("Limit whole-file transfers; each can still use multiple connections") },
                    leadingContent = { Icon(Icons.Default.Downloading, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { activeExpanded = true }) { Text("$maxActiveDownloads") }
                            DropdownMenu(expanded = activeExpanded, onDismissRequest = { activeExpanded = false }) {
                                (1..4).forEach { count ->
                                    DropdownMenuItem(text = { Text("$count at a time") }, onClick = { onMaxActiveChange(count); activeExpanded = false })
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Overall speed limit") },
                    supportingContent = { Text("Shared by every connection and active download") },
                    leadingContent = { Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { speedExpanded = true }) { Text(speedLimitLabel(speedLimit)) }
                            DropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                                listOf(0L, 256L * 1024, 512L * 1024, 1024L * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024).forEach { limit ->
                                    DropdownMenuItem(text = { Text(speedLimitLabel(limit)) }, onClick = { onSpeedLimitChange(limit); speedExpanded = false })
                                }
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(20.dp)) {
            ListItem(
                headlineContent = { Text("Privacy by design") },
                supportingContent = { Text("Links and download history stay on this device. FetchFlow has no account or analytics connection in this build.") },
                leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.secondary) }
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("FetchFlow 0.9.0 · Scheduled batches · notification controls", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun EmptyState(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onAction: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (title == "Nothing downloading") {
                Spacer(Modifier.height(14.dp)); OutlinedButton(onClick = onAction) { Text("Add a download") }
            }
        }
    }
}

@Composable
private fun AddDownloadDialog(initialUrl: String, wifiOnly: Boolean, partCount: Int, maxActiveDownloads: Int, onDismiss: () -> Unit, onAdd: (String, String, String, Boolean, Long) -> Unit, error: String?) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var sha256 by remember { mutableStateOf("") }
    var batchMode by remember(initialUrl) { mutableStateOf(initialUrl.lineSequence().count { it.isNotBlank() } > 1) }
    var scheduledAt by remember { mutableStateOf(0L) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a download", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = batchMode, onCheckedChange = { batchMode = it; if (it) scheduleError = null })
                    Text("Add multiple links", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(if (batchMode) "Links · one per line" else "Download link") },
                    placeholder = { Text(if (batchMode) "https://example.com/file1.zip\nhttps://example.com/file2.zip" else "https://example.com/file.zip") },
                    singleLine = !batchMode, minLines = if (batchMode) 4 else 1, maxLines = if (batchMode) 7 else 1,
                    modifier = Modifier.fillMaxWidth()
                )
                if (url.lineSequence().any { it.trim().startsWith("http://", ignoreCase = true) }) {
                    Text("This link uses unencrypted HTTP. The link and file contents may be observed or changed in transit; use HTTPS when available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!batchMode) {
                    OutlinedTextField(name, { name = it }, label = { Text("File name (optional)") }, placeholder = { Text("Use the server's file name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        sha256, { sha256 = it.filterNot { character -> character.isWhitespace() } },
                        label = { Text("Expected SHA-256 (optional)") },
                        placeholder = { Text("64 hexadecimal characters") },
                        supportingText = { Text("The file is saved only if its checksum matches.") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val now = Calendar.getInstance()
                        DatePickerDialog(context, { _, year, month, day ->
                            TimePickerDialog(context, { _, hour, minute ->
                                val chosen = Calendar.getInstance().apply {
                                    set(year, month, day, hour, minute, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                                if (chosen <= System.currentTimeMillis()) { scheduledAt = 0L; scheduleError = "Choose a future date and time." }
                                else { scheduledAt = chosen; scheduleError = null }
                            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text(if (scheduledAt > 0L) "Change time" else "Schedule for later") }
                    if (scheduledAt > 0L) TextButton(onClick = { scheduledAt = 0L; scheduleError = null }) { Text("Clear") }
                }
                if (scheduledAt > 0L) Text("${if (batchMode) "All links scheduled" else "Scheduled"} for ${java.text.DateFormat.getDateTimeInstance().format(Date(scheduledAt))}. Android may defer the start for battery or network conditions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (scheduleError != null) Text(scheduleError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (wifiOnly) Icons.Default.Wifi else Icons.Default.SignalCellularAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp)); Text(if (wifiOnly) "This download waits for an unmetered network" else "Metered mobile data is allowed", style = MaterialTheme.typography.bodySmall)
                }
                Text("Up to $partCount connections · $maxActiveDownloads downloads at once", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = scheduleError == null, onClick = { onAdd(url, name, sha256, batchMode, scheduledAt) }) {
                Text(when {
                    batchMode && scheduledAt > 0L -> "Schedule batch"
                    batchMode -> "Add to queue"
                    scheduledAt > 0L -> "Schedule download"
                    else -> "Start download"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DownloadDetailDialog(item: DownloadItem, onDismiss: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(item.state.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (item.state == "scheduled" && item.scheduledAt > 0L) Text("Scheduled for ${java.text.DateFormat.getDateTimeInstance().format(Date(item.scheduledAt))}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (item.state == "complete") 1f else item.progress }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(item.bytes)} of ${formatBytes(item.total)}", style = MaterialTheme.typography.bodySmall)
                if (item.expectedSha256.isNotBlank()) {
                    Text(
                        if (item.state == "complete") "SHA-256 verified" else "SHA-256 check will run before the file is saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == "complete") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (item.rangeSupported) "${item.completedPartIndexes.size} segments checkpointed · up to ${item.partCount} connections"
                    else "This server does not support segmented resume; a restart may begin from the start.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(item.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.reason.isNotBlank()) Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (item.state == "failed") TextButton(onClick = onRetry) { Text("Retry") }
            else if (item.state == "scheduled") TextButton(onClick = onPause) { Text("Pause") }
            else if (item.state == "paused") TextButton(onClick = onResume) { Text("Resume") }
            else if (item.state == "downloading" || item.state == "queued") TextButton(onClick = onPause) { Text("Pause") }
            if (item.state != "complete" && item.state != "cancelled") TextButton(onClick = onCancel) { Text("Cancel") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun openDownload(context: android.content.Context, store: DownloadStore, item: DownloadItem) {
    val uri = store.openUri(item) ?: run {
        android.widget.Toast.makeText(context, "The downloaded file is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure { android.widget.Toast.makeText(context, "No app could open this file.", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun shareDownload(context: android.content.Context, store: DownloadStore, item: DownloadItem) {
    val uri = store.openUri(item) ?: run {
        android.widget.Toast.makeText(context, "The downloaded file is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, item.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
    }.onFailure { android.widget.Toast.makeText(context, "Could not share this file.", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun openNotificationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
    }.onFailure {
        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

private fun statusIcon(state: String) = when (state) {
    "complete" -> Icons.Default.CheckCircle
    "failed" -> Icons.Default.ErrorOutline
    "paused" -> Icons.Default.PauseCircle
    "scheduled" -> Icons.Default.Event
    "queued" -> Icons.Default.Schedule
    "cancelled" -> Icons.Default.Cancel
    else -> Icons.Default.Downloading
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var index = -1
    do { value /= 1024.0; index++ } while (value >= 1024 && index < units.lastIndex)
    return "${DecimalFormat("0.#").format(value)} ${units[index]}"
}

private fun speedLimitLabel(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "Unlimited"
    bytesPerSecond % (1024L * 1024) == 0L -> "${bytesPerSecond / (1024L * 1024)} MB/s"
    else -> "${bytesPerSecond / 1024} KB/s"
}

private fun incomingLinks(intent: Intent?): List<String> {
    if (intent == null) return emptyList()
    val candidates = mutableListOf<String>()
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString?.let(candidates::add)
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(candidates::add)
            if (intent.action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()?.let(candidates::add)
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { candidates.add(it.toString()) }
            }
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).text?.toString()?.let(candidates::add)
                    clip.getItemAt(index).uri?.toString()?.let(candidates::add)
                }
            }
        }
    }
    val result = LinkedHashSet<String>()
    candidates.forEach { candidate ->
        Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).findAll(candidate).forEach { match ->
            match.value.trimEnd('.', ',', ';', ')', ']', '}').takeIf { it.isNotBlank() }?.let(result::add)
        }
        candidate.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let(result::add)
    }
    return result.toList()
}
