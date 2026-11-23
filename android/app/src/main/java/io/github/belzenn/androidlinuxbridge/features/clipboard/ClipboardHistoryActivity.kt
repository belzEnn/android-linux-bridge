package io.github.belzenn.androidlinuxbridge.features.clipboard

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.belzenn.androidlinuxbridge.ui.theme.AndroidLinuxBridgeTheme
import io.github.belzenn.androidlinuxbridge.ui.theme.appearancePreferences
import io.github.belzenn.androidlinuxbridge.ui.theme.useDarkTheme
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ClipboardHistoryActivity : ComponentActivity() {
    private var history by mutableStateOf(emptyList<ClipboardHistoryItem>())
    private var sendResult by mutableStateOf<ClipboardSendResult?>(null)
    private var capturedCurrentClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        history = ClipboardHistory.load(this)
        setContent {
            val appearance = remember { appearancePreferences(this).getString("theme", "system") }
            AndroidLinuxBridgeTheme(darkTheme = useDarkTheme(appearance)) {
                var query by rememberSaveable { mutableStateOf("") }
                val visible = history.filter { it.text.contains(query, ignoreCase = true) }
                BackHandler { closeHistory() }
                Box(
                    Modifier.fillMaxSize().background(Color.Transparent).clickable { closeHistory() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        onClick = {},
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Clipboard history", style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = ::closeHistory,
                                    modifier = Modifier.semantics { contentDescription = "Close history" },
                                ) {
                                    Text("×", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                            sendResult?.let { result ->
                                Text(
                                    when (result) {
                                        ClipboardSendResult.SENT -> "Current Android clipboard sent to Linux"
                                        ClipboardSendResult.ALREADY_SYNCED -> "Current clipboard is already synchronized"
                                        ClipboardSendResult.EMPTY -> "Copy text on Android, then open this tile"
                                        ClipboardSendResult.INVALID -> "Current clipboard is empty or too large"
                                        ClipboardSendResult.OFFLINE -> "Connect to Linux to send the current clipboard"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (result == ClipboardSendResult.OFFLINE || result == ClipboardSendResult.INVALID)
                                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            when {
                                history.isEmpty() -> Text("No synchronized clipboard items yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 20.dp))
                                visible.isEmpty() -> Text("No matching items",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 20.dp))
                                else -> LazyColumn(
                                    Modifier.heightIn(max = 440.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(visible, key = { it.id }) { item ->
                                        Surface(
                                            onClick = {
                                                ClipboardHistory.copy(this@ClipboardHistoryActivity, item)
                                                closeHistory()
                                            },
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp,
                                                bottom = 10.dp, end = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(formatTime(item.createdAt), style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        ClipboardHistory.delete(this@ClipboardHistoryActivity, item.id)
                                                        history = history.filterNot { it.id == item.id }
                                                    },
                                                    modifier = Modifier.semantics { contentDescription = "Delete clipboard item" },
                                                ) { Text("×", style = MaterialTheme.typography.titleLarge) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !capturedCurrentClipboard) {
            capturedCurrentClipboard = true
            sendResult = ClipboardHistory.sendCurrent(this)
        }
    }

    private fun closeHistory() {
        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun formatTime(value: String): String = runCatching {
        OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(value)
}
