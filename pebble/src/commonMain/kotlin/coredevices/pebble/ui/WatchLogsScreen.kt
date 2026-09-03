package coredevices.pebble.ui

import CoreNav
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.rememberLibPebble
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.packets.AppLogReceivedMessage
import io.rebble.libpebblecommon.packets.AppLogShippingControlMessage
import io.rebble.libpebblecommon.services.LogLevel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Watch logs on the phone, copyable. Two sources, because the firmware has two:
 * app logs stream live over the protocol once shipping is switched on; firmware
 * logs only exist as a dump the watch produces on request, so those are pulled.
 */
@Composable
fun WatchLogsScreen(coreNav: CoreNav) {
    val libPebble = rememberLibPebble()
    val watches by libPebble.watches.collectAsState()
    val connected = watches.firstOrNull { it is ConnectedPebbleDevice }
    val messages = connected as? ConnectedPebble.Messages
    val logs = connected as? ConnectedPebble.Logs
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lines = remember { mutableStateListOf<String>() }
    var live by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(live, messages) {
        val target = messages ?: return@LaunchedEffect
        target.sendPPMessage(AppLogShippingControlMessage(live))
        if (!live) return@LaunchedEffect
        target.inboundMessages
            .filterIsInstance<AppLogReceivedMessage>()
            .collect { message ->
                lines += formatAppLog(message)
                if (lines.size > MAX_LINES) lines.removeAt(0)
            }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun pullFirmwareLog() {
        val source = logs ?: return
        pulling = true
        scope.launch {
            lines += "--- firmware log dump ---"
            val path = source.gatherLogs()
            if (path == null) {
                lines += "(no logs returned)"
            } else {
                val text = SystemFileSystem.source(path).buffered().use { it.readString() }
                lines += text.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES)
            }
            pulling = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch logs") },
                navigationIcon = {
                    IconButton(onClick = { coreNav.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) },
                        enabled = lines.isNotEmpty(),
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy all") }
                    IconButton(onClick = { lines.clear() }, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = live, onCheckedChange = { live = it }, enabled = messages != null)
                Text("Live app logs", Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = ::pullFirmwareLog, enabled = logs != null && !pulling) {
                    Text(if (pulling) "Pulling..." else "Pull firmware log")
                }
            }
            if (connected == null) {
                Text(
                    "No connected watch.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun formatAppLog(message: AppLogReceivedMessage): String {
    val seconds = message.timestamp.get().toLong()
    val hh = (seconds / 3600) % 24
    val mm = (seconds / 60) % 60
    val ss = seconds % 60
    val time = "${hh.pad()}:${mm.pad()}:${ss.pad()}"
    val level = LogLevel.fromCode(message.level.get()).str
    val file = message.filename.get().trimEnd(' ')
    return "$time $level $file:${message.lineNumber.get()} ${message.message.get()}"
}

private fun Long.pad(): String = toString().padStart(2, '0')

private const val MAX_LINES = 2000
