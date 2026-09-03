package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import coredevices.pebble.services.StoneBuild
import coredevices.pebble.services.StoneBundleInstaller
import coredevices.pebble.services.StoneChannel
import coredevices.pebble.services.StoneChannels
import coredevices.ui.PebbleElevatedButton
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The trunk Stone channel on the device card: what it publishes, whether the
 * watch is on it, an Update with the build's notes, a roll back to whatever was
 * on the watch before the last update, and the way into the watch log screen.
 */
@Composable
fun StoneChannelPanel(watch: PebbleDevice, navBarNav: NavBarNav) {
    val stone: StoneChannels = koinInject()
    val installer: StoneBundleInstaller = koinInject()
    val appContext: AppContext = koinInject()
    val settings: Settings = koinInject()
    val scope = rememberCoroutineScope()
    var trunk by remember { mutableStateOf<StoneChannel?>(null) }
    var builds by remember { mutableStateOf<List<StoneBuild>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<StoneBuild?>(null) }
    var previousVersion by remember { mutableStateOf(settings.getStringOrNull(PREVIOUS_VERSION_KEY)) }

    LaunchedEffect(Unit) {
        stone.channels()
            .onSuccess { channels -> trunk = channels.firstOrNull { it.isTrunk } ?: channels.firstOrNull() }
            .onFailure { loadError = it.message ?: "Could not reach the channel server" }
        trunk?.let { channel ->
            stone.builds(channel.channel).onSuccess { builds = it }
        }
    }

    val runningVersion = (watch as? KnownPebbleDevice)?.runningFwVersion
    val firmware = watch as? ConnectedPebble.Firmware
    val channel = trunk
    val latest = channel?.version
    val onWatch = latest != null && latest == runningVersion
    val latestBuild = builds.firstOrNull { it.version == latest }
    val rollbackBuild = previousVersion
        ?.takeIf { it != runningVersion }
        ?.let { previous -> builds.firstOrNull { it.version == previous } }

    fun install(build: StoneBuild) {
        val target = firmware ?: return
        val channelName = channel?.channel ?: return
        busy = true
        // Remember what we are leaving so the next card can offer the way back.
        runningVersion?.let {
            settings.putString(PREVIOUS_VERSION_KEY, it)
            previousVersion = it
        }
        scope.launch {
            installer.installOnWatch(channelName, build, target, getTempFwPath(appContext)) { status = it }
            busy = false
        }
    }

    confirming?.let { build ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Install ${build.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val notes = build.notes.ifEmpty { listOf("No notes for this build.") }
                    notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    build.commitShort?.let {
                        Text(
                            "commit $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    install(build)
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Stone",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val error = loadError
        when {
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            channel == null -> Text("Checking channel...", style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(
                    buildString {
                        append(channel.channel)
                        latest?.let { append("  ").append(it) }
                        channel.commitShort?.let { append(" (").append(it).append(")") }
                        if (onWatch) append("  -  on watch")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row {
                    if (latest != null && !onWatch && firmware != null) {
                        PebbleElevatedButton(
                            text = "Update to $latest",
                            onClick = {
                                // Notes come from the builds list; if that failed, fall back
                                // to a bare build so the update is still one tap away.
                                confirming = latestBuild ?: StoneBuild(
                                    channel = channel.channel,
                                    version = latest,
                                    commitShort = channel.commitShort,
                                )
                            },
                            enabled = !busy,
                            icon = Icons.Default.SystemUpdateAlt,
                            contentDescription = "Update Stone firmware",
                            primaryColor = true,
                            modifier = Modifier.padding(end = 8.dp, top = 5.dp, bottom = 5.dp),
                        )
                    }
                    if (rollbackBuild != null && firmware != null) {
                        PebbleElevatedButton(
                            text = "Roll back to ${rollbackBuild.version}",
                            onClick = { confirming = rollbackBuild },
                            enabled = !busy,
                            icon = Icons.Default.History,
                            contentDescription = "Roll back Stone firmware",
                            primaryColor = false,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (watch is ConnectedPebble.Messages) {
            PebbleElevatedButton(
                text = "Watch logs",
                onClick = { navBarNav.navigateTo(PebbleRoutes.WatchLogsRoute) },
                icon = Icons.Default.Terminal,
                contentDescription = "Watch logs",
                primaryColor = false,
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

/**
 * Every debug setting, rendered in place instead of behind
 * Settings > Phone > Debug > Show debug options.
 */
@Composable
fun InlineDebugOptions(navBarNav: NavBarNav, snackbarDisplay: SnackbarDisplay) {
    val state = rememberSettingsItemsState(navBarNav, snackbarDisplay) ?: return
    val items = state.rawSettingsItems.filter {
        it.isDebugSetting && it.title != "Show debug options" && it.show()
    }
    if (items.isEmpty()) return
    Text(
        "Debug",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    items.forEach { it.Item() }
}

private const val PREVIOUS_VERSION_KEY = "stone_previous_fw_version"
