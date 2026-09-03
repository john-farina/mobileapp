package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * The trunk Stone channel on the device card: what the channel currently
 * publishes, whether the watch is on it, and a one-tap update when it is not.
 */
@Composable
fun StoneChannelPanel(watch: PebbleDevice) {
    val stone: StoneChannels = koinInject()
    val installer: StoneBundleInstaller = koinInject()
    val appContext: AppContext = koinInject()
    val scope = rememberCoroutineScope()
    var trunk by remember { mutableStateOf<StoneChannel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stone.channels()
            .onSuccess { channels -> trunk = channels.firstOrNull { it.isTrunk } ?: channels.firstOrNull() }
            .onFailure { loadError = it.message ?: "Could not reach the channel server" }
    }

    val runningVersion = (watch as? KnownPebbleDevice)?.runningFwVersion
    val firmware = watch as? ConnectedPebble.Firmware
    val channel = trunk
    val latest = channel?.version
    val onWatch = latest != null && latest == runningVersion

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
            channel == null -> Text("Checking channel…", style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(
                    buildString {
                        append(channel.channel)
                        latest?.let { append("  ").append(it) }
                        channel.commitShort?.let { append(" (").append(it).append(")") }
                        if (onWatch) append("  ·  on watch")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (latest != null && !onWatch && firmware != null) {
                    PebbleElevatedButton(
                        text = "Update to $latest",
                        onClick = {
                            busy = true
                            scope.launch {
                                val build = stone.builds(channel.channel).getOrNull()
                                    ?.firstOrNull { it.version == latest }
                                if (build == null) {
                                    status = "Build $latest is not on the server"
                                } else {
                                    installer.installOnWatch(
                                        channel.channel, build, firmware, getTempFwPath(appContext),
                                    ) { status = it }
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        icon = Icons.Default.SystemUpdateAlt,
                        contentDescription = "Update Stone firmware",
                        primaryColor = true,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * Every debug setting, rendered in place instead of behind
 * Settings → Phone → Debug → Show debug options.
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
