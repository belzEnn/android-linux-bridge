package io.github.belzenn.androidlinuxbridge.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import io.github.belzenn.androidlinuxbridge.ui.theme.AndroidLinuxBridgeTheme
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class SetupStatus(
    val notificationAccess: Boolean = false,
    val notifications: Boolean = false,
    val localNetwork: Boolean = true,
    val background: Boolean = false
) {
    val permissionsGranted: Boolean get() = notificationAccess && notifications && localNetwork
    val complete: Boolean get() = permissionsGranted && background
}

private enum class Page(val title: String) {
    HOME("Home"), LOGS("Logs")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeApp(
    setup: SetupStatus,
    onNotificationAccess: () -> Unit,
    onPermissions: () -> Unit,
    onAppSettings: () -> Unit,
    onNotificationSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onComputerSelected: (DiscoveredComputer) -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("bridge_appearance", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(setup.complete) {
        if (setup.complete) showSetup = false
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    AndroidLinuxBridgeTheme(darkTheme = dark, dynamicColor = false) {
        val setupContent: @Composable () -> Unit = {
            SetupOptions(setup, onNotificationAccess, onPermissions, onAppSettings, onNotificationSettings, onBatterySettings)
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= 840.dp
                val sidebar: @Composable () -> Unit = {
                    Sidebar(page, theme, setup.complete, onPage = {
                        page = it
                        scope.launch { drawer.close() }
                    }, onTheme = {
                        theme = it
                        preferences.edit().putString("theme", it).apply()
                    }, onSetup = { showSetup = true })
                }
                val content: @Composable () -> Unit = {
                    Scaffold(
                        topBar = {
                            TopAppBar(title = { Text(page.title) }, navigationIcon = {
                                if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Text("☰", modifier = Modifier.semantics { contentDescription = "Open sidebar" })
                                }
                            })
                        }
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
                            when (page) {
                                Page.HOME -> HomePage(onReconnect, onComputerSelected, setup.permissionsGranted, { showSetup = true })
                                Page.LOGS -> LogsPage()
                            }
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Surface(Modifier.width(240.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) { sidebar() }
                        VerticalDivider()
                        Box(Modifier.weight(1f)) { content() }
                    }
                } else {
                    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
                    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                        ModalDrawerSheet { Box(Modifier.width(280.dp)) { sidebar() } }
                    }, content = content)
                }
            }
        }
        if (!setup.complete && (showSetup || (!setupDismissed && !setup.permissionsGranted))) {
            AlertDialog(
                onDismissRequest = { setupDismissed = true; showSetup = false },
                title = { Text("Set up your bridge") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enable access for notification forwarding and a reliable connection. Background access is optional.")
                        setupContent()
                    }
                },
                confirmButton = {
                    TextButton(onClick = { setupDismissed = true; showSetup = false }) {
                        Text("Continue")
                    }
                }
            )
        }
    }
}

@Composable
private fun Sidebar(page: Page, theme: String, setupComplete: Boolean, onPage: (Page) -> Unit, onTheme: (String) -> Unit, onSetup: () -> Unit) {
    Column(Modifier.fillMaxHeight().padding(12.dp)) {
        Text("Android Linux Bridge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Page.entries.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(destination.title) }, selected = page == destination,
                    onClick = { onPage(destination) },
                    icon = { NavigationIcon(destination) },
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
        TextButton(onClick = onSetup, enabled = !setupComplete, modifier = Modifier.fillMaxWidth()) {
            Text(if (setupComplete) "All permissions enabled" else "Permissions & background")
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("system" to "Use device theme", "light" to "Light theme", "dark" to "Dark theme").forEach { (value, label) ->
                FilledIconToggleButton(checked = theme == value, onCheckedChange = { onTheme(value) }, modifier = Modifier.semantics { contentDescription = label }) {
                    ThemeIcon(value)
                }
            }
        }
        Text(when (theme) { "light" -> "Light theme"; "dark" -> "Dark theme"; else -> "Following device theme" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp, top = 4.dp))
    }
}

@Composable
private fun NavigationIcon(page: Page) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(1.8.dp.toPx())
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        when (page) {
            Page.HOME -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .1f, size.height * .45f)
                    lineTo(size.width * .5f, size.height * .1f)
                    lineTo(size.width * .9f, size.height * .45f)
                    moveTo(size.width * .2f, size.height * .4f)
                    lineTo(size.width * .2f, size.height * .9f)
                    lineTo(size.width * .8f, size.height * .9f)
                    lineTo(size.width * .8f, size.height * .4f)
                }
                drawPath(path, color, style = stroke)
            }
            Page.LOGS -> repeat(3) { index ->
                val y = .25f + index * .25f
                drawLine(color, point(.15f, y), point(.85f, y), stroke.width)
            }
        }
    }
}

@Composable
private fun ThemeIcon(theme: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(1.8.dp.toPx())
        when (theme) {
            "light" -> {
                drawCircle(color, size.width * .22f, style = stroke)
                repeat(8) {
                    val angle = it * Math.PI / 4
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(color, center + direction * (size.width * .34f), center + direction * (size.width * .47f), stroke.width)
                }
            }
            "dark" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .65f, size.height * .08f)
                    cubicTo(-size.width * .2f, size.height * .05f, 0f, size.height * 1.2f, size.width * .87f, size.height * .77f)
                    cubicTo(size.width * .38f, size.height * .8f, size.width * .23f, size.height * .35f, size.width * .65f, size.height * .08f)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
            else -> {
                drawRoundRect(color, Offset(size.width * .2f, size.height * .05f), Size(size.width * .6f, size.height * .9f), CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, Offset(size.width * .4f, size.height * .8f), Offset(size.width * .6f, size.height * .8f), stroke.width)
            }
        }
    }
}

@Composable
private fun SetupOptions(setup: SetupStatus, onAccess: () -> Unit, onPermissions: () -> Unit, onAppSettings: () -> Unit, onNotifications: () -> Unit, onBattery: () -> Unit) {
    if (!setup.notificationAccess) {
        SetupRow("Notification access", "Forward notifications to your computer.", false, onAccess)
    }
    if (!setup.notifications) {
        SetupRow("App notifications", "Display connection status on this device.", false, onNotifications)
    }
    if (!setup.localNetwork) {
        SetupRow("Local network", "Discover and connect to computers on your Wi-Fi.", false, onPermissions)
        TextButton(onClick = onAppSettings) { Text("Open app settings if permission was denied") }
    }
    if (!setup.background) {
        SetupRow("Background activity", "Optional: allow unrestricted battery use to keep the bridge running.", false, onBattery)
    }
}

@Composable
private fun SetupRow(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(if (enabled) "Enabled · Settings" else "Enable") }
        }
    }
}

@Composable
private fun HomePage(onReconnect: () -> Unit, onComputerSelected: (DiscoveredComputer) -> Unit, setupComplete: Boolean, onSetup: () -> Unit) {
    var showComputers by rememberSaveable { mutableStateOf(false) }
    val status = BridgeState.connectionStatus.value
    val foreground = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(20.dp))
        Canvas(Modifier.size(144.dp, 112.dp)) {
            val stroke = Stroke(3.dp.toPx())
            drawRoundRect(foreground.copy(alpha = .06f), size = Size(size.width, size.height * .75f), cornerRadius = CornerRadius(12.dp.toPx()))
            drawRoundRect(foreground.copy(alpha = .85f), topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(size.width - 4.dp.toPx(), size.height * .75f), cornerRadius = CornerRadius(12.dp.toPx()), style = stroke)
            drawLine(foreground, Offset(size.width / 2, size.height * .77f), Offset(size.width / 2, size.height * .95f), stroke.width)
            drawLine(foreground, Offset(size.width * .3f, size.height * .95f), Offset(size.width * .7f, size.height * .95f), stroke.width)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                BridgeState.computerName.value.ifBlank { "Select a computer" },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (BridgeState.distribution.value.isNotBlank()) {
                Text(
                    BridgeState.distribution.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Text(when (status) {
            ConnectionStatus.CONNECTED -> "●  Connected"
            ConnectionStatus.CONNECTING -> "●  Connecting…"
            ConnectionStatus.AWAITING_APPROVAL -> "●  Waiting for approval…"
            else -> "●  Disconnected"
        }, color = if (status == ConnectionStatus.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { showComputers = !showComputers }) {
            Text(if (showComputers) "Hide computers" else "Find computers")
        }
        if (showComputers) NearbyComputers(onComputerSelected)
        if (BridgeState.serverHost.value.isNotBlank() && status != ConnectionStatus.CONNECTED && status != ConnectionStatus.CONNECTING) {
            OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
        }
        if (!setupComplete) TextButton(onClick = onSetup) { Text("Finish setup") }
    }
}

@Composable
private fun NearbyComputers(onSelected: (DiscoveredComputer) -> Unit) {
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("Nearby computers", style = MaterialTheme.typography.headlineSmall)
            Text("Connect both devices to the same local network.", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (BridgeState.computers.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Looking for computers…", style = MaterialTheme.typography.titleMedium)
                    Text("Open Android Linux Bridge on your Linux computer. It will appear here automatically.")
                }
            }
        }
        BridgeState.computers.forEach { computer ->
            OutlinedCard(onClick = { onSelected(computer) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(computer.computerName, style = MaterialTheme.typography.titleMedium)
                    Text(computer.distribution, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Connect", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun LogsPage() {
    val logs = BridgeState.logs
    val state = rememberLazyListState()
    val latest = logs.lastOrNull()
    LaunchedEffect(latest) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Connection activity", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = BridgeState::clearLogs, enabled = logs.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)),
            state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (logs.isEmpty()) item { Text("No activity yet") }
            items(logs) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}
