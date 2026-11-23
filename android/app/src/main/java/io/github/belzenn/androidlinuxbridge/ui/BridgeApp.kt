package io.github.belzenn.androidlinuxbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import io.github.belzenn.androidlinuxbridge.ui.theme.AndroidLinuxBridgeTheme
import io.github.belzenn.androidlinuxbridge.ui.theme.appearancePreferences
import io.github.belzenn.androidlinuxbridge.ui.theme.useDarkTheme
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
    onReconnect: () -> Unit,
    onAddClipboardTile: () -> Unit,
    onSetupBluetooth: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val preferences = remember { appearancePreferences(context) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
    val dark = useDarkTheme(theme)
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(setup.complete) {
        if (setup.complete) showSetup = false
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    AndroidLinuxBridgeTheme(darkTheme = dark) {
        if (BridgeState.findingPhone.value) {
            AlertDialog(
                onDismissRequest = { BridgeState.stopFindingPhone?.invoke() },
                title = { Text("Find phone") },
                text = { Text("Your computer is ringing this phone.") },
                confirmButton = {
                    Button(onClick = { BridgeState.stopFindingPhone?.invoke() }) { Text("Stop ringing") }
                }
            )
        }
        BridgeState.pairingFingerprint.value?.let { fingerprint ->
            AlertDialog(
                onDismissRequest = { BridgeState.confirmFingerprint?.invoke(false) },
                title = { Text("Verify computer key") },
                text = { Text("Compare every group with the fingerprint shown on your Linux computer.\n\n" +
                    fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace) },
                confirmButton = { TextButton(onClick = { BridgeState.confirmFingerprint?.invoke(true) }) { Text("Fingerprints match") } },
                dismissButton = { TextButton(onClick = { BridgeState.confirmFingerprint?.invoke(false) }) { Text("Cancel") } }
            )
        }

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
                    }, onSetup = { showSetup = true }, onAddClipboardTile = onAddClipboardTile)
                }
                val content: @Composable () -> Unit = {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(page.title, style = MaterialTheme.typography.titleLarge) },
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                navigationIcon = {
                                    if (!wide) IconButton(
                                        onClick = { scope.launch { drawer.open() } },
                                        modifier = Modifier.semantics { contentDescription = "Open sidebar" },
                                    ) {
                                        NavigationIcon(Page.LOGS)
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                                when (page) {
                                    Page.HOME -> HomePage(
                                        onReconnect, onComputerSelected, setup.permissionsGranted,
                                        { showSetup = true }, onSetupBluetooth,
                                    )
                                    Page.LOGS -> LogsPage()
                                }
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
                        ModalDrawerSheet(
                            modifier = Modifier.width(280.dp),
                            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) { sidebar() }
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
private fun Sidebar(page: Page, theme: String, setupComplete: Boolean, onPage: (Page) -> Unit, onTheme: (String) -> Unit, onSetup: () -> Unit, onAddClipboardTile: () -> Unit) {
    Column(Modifier.fillMaxHeight().padding(12.dp)) {
        Text("Android Linux Bridge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Page.entries.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(destination.title) }, selected = page == destination,
                    onClick = { onPage(destination) },
                    icon = { NavigationIcon(destination) },
                    shape = MaterialTheme.shapes.medium,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
            TextButton(onClick = onSetup, enabled = !setupComplete, modifier = Modifier.fillMaxWidth()) {
                Text(if (setupComplete) "All permissions enabled" else "Permissions & background")
            }
            TextButton(onClick = onAddClipboardTile, modifier = Modifier.fillMaxWidth()) {
                Text("Add clipboard tile")
            }
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
        SetupRow("Notification access", "Forward notifications to your computer.", onAccess)
    }
    if (!setup.notifications) {
        SetupRow("App notifications", "Display connection status on this device.", onNotifications)
    }
    if (!setup.localNetwork) {
        SetupRow("Local network", "Discover and connect to computers on your Wi-Fi.", onPermissions)
        TextButton(onClick = onAppSettings) { Text("Open app settings if permission was denied") }
    }
    if (!setup.background) {
        SetupRow("Background activity", "Optional: allow unrestricted battery use to keep the bridge running.", onBattery)
    }
}

@Composable
private fun SetupRow(title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text("Enable") }
        }
    }
}

@Composable
private fun LogsPage() {
    val logs = BridgeState.logs
    val state = rememberLazyListState()
    val latest = logs.lastOrNull()
    LaunchedEffect(latest) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.lastIndex) }
    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Connection activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = BridgeState::clearLogs, enabled = logs.isNotEmpty()) { Text("Clear") }
        }
        OutlinedCard(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (logs.isEmpty()) item {
                    Text("No activity yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(logs) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
