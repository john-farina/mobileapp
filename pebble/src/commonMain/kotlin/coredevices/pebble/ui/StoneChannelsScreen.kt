package coredevices.pebble.ui

import CoreNav
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import coredevices.pebble.services.StoneBuild
import coredevices.pebble.services.StoneBundleInstaller
import coredevices.pebble.services.StoneChannel
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.StoneChannels
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.AppContext
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Every branch of the firmware fork that has published a build, and what it
 * published. A channel is still listed only while its branch is live --
 * `stone-cleanup.yml` retires it once the work lands on trunk -- so this doubles
 * as the list of branches currently in flight.
 */
@Composable
fun StoneChannelsScreen(coreNav: CoreNav) {
    val stone: StoneChannels = koinInject()
    val installer: StoneBundleInstaller = koinInject()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val libPebble = rememberLibPebble()
    val appContext: AppContext = koinInject()
    val watches by libPebble.watches.collectAsState()

    // The build currently on the wrist, so the list can say which one you are
    // running rather than making you compare version strings by eye.
    val connected = watches.firstOrNull { it is ConnectedPebbleDevice }
    val runningVersion = (connected as? KnownPebbleDevice)?.runningFwVersion

    // Per-build progress, keyed by version.
    val status = remember { mutableStateMapOf<String, String>() }

    fun install(channelName: String, build: StoneBuild) {
        // The watch is already connected and collected above, so use it directly
        // rather than re-acquiring it -- the helper that waits for a connection
        // is private to DebugFirmwareSideload.
        val firmware = connected as? ConnectedPebble.Firmware
        if (firmware == null) {
            scope.launch { snackbar.showSnackbar("No connected watch that can take a firmware update") }
            return
        }
        scope.launch {
            installer.installOnWatch(channelName, build, firmware, getTempFwPath(appContext)) {
                status[build.version] = it
            }
                .onFailure { snackbar.showSnackbar(it.message ?: "Install failed") }
                .onSuccess { snackbar.showSnackbar("Sent ${build.version} to the watch") }
        }
    }

    var channels by remember { mutableStateOf<List<StoneChannel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val expanded = remember { mutableStateMapOf<String, List<StoneBuild>>() }
    val expanding = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun load() {
        loading = true
        error = null
        stone.channels()
            .onSuccess { channels = it; expanded.clear() }
            .onFailure { error = it.message ?: "Could not reach the channel server" }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stone channels") },
                navigationIcon = {
                    IconButton(onClick = { coreNav.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && channels.isEmpty() -> Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                error != null -> Column(Modifier.padding(16.dp)) {
                    Text("Could not load channels", style = MaterialTheme.typography.titleMedium)
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    stone.baseUrl?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    } ?: Text(
                        "No channel server is configured in this build.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                channels.isEmpty() -> Text(
                    "No channels have published a build yet.",
                    modifier = Modifier.padding(16.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(channels, key = { it.channel }) { channel ->
                        ChannelCard(
                            channel = channel,
                            builds = expanded[channel.channel],
                            loadingBuilds = expanding[channel.channel] == true,
                            urlFor = { b ->
                                b.bundle?.let { installer.bundleUrl(channel.channel, it) }
                            },
                            onShowUrl = { url ->
                                scope.launch { snackbar.showSnackbar(url) }
                            },
                            runningVersion = runningVersion,
                            statusFor = { b -> status[b.version] },
                            onInstall = { b -> install(channel.channel, b) },
                            onToggle = {
                                if (expanded.containsKey(channel.channel)) {
                                    expanded.remove(channel.channel)
                                } else {
                                    expanding[channel.channel] = true
                                    scope.launch {
                                        stone.builds(channel.channel)
                                            .onSuccess { expanded[channel.channel] = it }
                                            .onFailure { expanded[channel.channel] = emptyList() }
                                        expanding[channel.channel] = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: StoneChannel,
    builds: List<StoneBuild>?,
    loadingBuilds: Boolean,
    urlFor: (StoneBuild) -> String?,
    onShowUrl: (String) -> Unit,
    runningVersion: String?,
    statusFor: (StoneBuild) -> String?,
    onInstall: (StoneBuild) -> Unit,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onToggle() },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.channel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (channel.isTrunk) FontWeight.Bold else FontWeight.Normal,
                )
                if (channel.isTrunk) {
                    Text(
                        "  trunk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            channel.version?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            Row {
                channel.commitShort?.let {
                    Text("$it  ", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                channel.builtAt?.let {
                    Text(it.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (loadingBuilds) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                }
            }

            builds?.let { list ->
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                if (list.isEmpty()) {
                    Text("No build history.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${list.size} build${if (list.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    list.forEach { build ->
                        val isRunning = runningVersion != null && build.version == runningVersion
                        Column(Modifier.padding(top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    build.version,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (isRunning) {
                                    Text(
                                        "  ON WATCH",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            build.notes.firstOrNull()?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            build.builtAt?.let {
                                Text(
                                    it.take(16).replace('T', ' '),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            urlFor(build)?.let { url ->
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onShowUrl(url) },
                                )
                            }
                            if (build.sha256 == null) {
                                Text(
                                    "no sha256 published - cannot be verified",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            statusFor(build)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                            if (!isRunning && build.sha256 != null) {
                                Button(
                                    onClick = { onInstall(build) },
                                    modifier = Modifier.padding(top = 4.dp),
                                ) { Text("Install") }
                            }
                        }
                    }
                }
            }
        }
    }
}
