package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.R
import app.barq.engine.QrPayload
import app.barq.crypto.Tls
import app.barq.data.Settings
import app.barq.net.BleScanner
import app.barq.net.PairingDeclined
import app.barq.net.PairingTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.barq.net.NearbyDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Nearby devices as bubbles on concentric rings; distance from the centre
 * follows signal strength. Tapping one reads its connection payload over GATT.
 */
@Composable
fun DiscoverScreen(
    fileCount: Int, onBack: () -> Unit, onScanQr: () -> Unit, onPicked: (QrPayload) -> Unit,
    onBroadcast: () -> Unit = {}, onPickedAware: (Int) -> Unit = {}, error: String? = null,
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val fetchFailed = stringResource(R.string.discover_fetch_failed)
    val deniedText = stringResource(R.string.discover_denied)
    val timeoutText = stringResource(R.string.discover_timeout)
    val lostText = stringResource(R.string.discover_ble_lost)
    val scanner = remember { BleScanner(ctx) }
    val bleDevices by scanner.devices.collectAsStateWithLifecycle()
    // Wi-Fi Aware peers (FR-14) join the same radar; they have no RSSI, so they sit on the middle ring.
    val aware = remember { app.barq.TransferController.awareScanner(ctx) }
    val awarePeers by aware.devices.collectAsStateWithLifecycle()
    var awareOn by remember { mutableStateOf(false) }
    val devices = remember(bleDevices, awarePeers) {
        bleDevices + awarePeers.map { NearbyDevice("aware:${it.id}", it.name, -70, it.lastSeen) }
    }
    var scanning by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        scanning = scanner.start()
        onDispose { scanner.stop(); app.barq.TransferController.stopAwareScan() }
    }
    LaunchedEffect(Unit) { awareOn = runCatching { aware.start() }.getOrDefault(false) }
    LaunchedEffect(Unit) { while (isActive) { delay(2000); scanner.prune() } }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(stringResource(R.string.discover_title), subtitle = stringResource(R.string.send_picked_count, fileCount), onBack = onBack)

        Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            Ring(340); Ring(250); Ring(160, fill = c.primary.copy(alpha = 0.04f))
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(c.primary).border(10.dp, c.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(BarqIcons.Bolt, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(30.dp)) }

            val shown = devices.take(6)
            shown.forEachIndexed { i, d ->
                val angle = Math.toRadians(-90.0 + i * (360.0 / shown.size.coerceAtLeast(1)) + 20.0)
                val radius = radiusFor(d.rssi)
                val x = (radius * cos(angle)).dp
                val y = (radius * sin(angle)).dp
                DeviceBubble(
                    d, busy = fetching == d.address, modifier = Modifier.offset(x = x, y = y),
                    onClick = {
                        if (fetching != null) return@DeviceBubble
                        if (d.address.startsWith("aware:")) {
                            onPickedAware(d.address.removePrefix("aware:").toInt())
                            return@DeviceBubble
                        }
                        fetching = d.address; fetchError = null; waiting = false
                        scope.launch {
                            // Ask first: the other phone shows "X wants to connect" and only then releases its network details.
                            runCatching {
                                val me = withContext(Dispatchers.Default) { Tls.identity().fingerprint }
                                scanner.fetchPayload(d.address, Settings.get(ctx).deviceName, me, onWaiting = { waiting = true })
                            }
                                .onSuccess { onPicked(it) }
                                .onFailure { e ->
                                    fetchError = when {
                                        e is PairingDeclined -> deniedText
                                        e is PairingTimeout -> timeoutText
                                        e.message?.startsWith("Bluetooth connection lost") == true -> lostText
                                        else -> fetchFailed
                                    }
                                }
                            fetching = null; waiting = false
                        }
                    },
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanning) Box(Modifier.size(8.dp).clip(CircleShape).background(c.primary))
                Text(
                    when {
                        !scanning && !awareOn -> stringResource(R.string.discover_bt_off)
                        fetching != null -> stringResource(if (waiting) R.string.discover_waiting else R.string.discover_fetching)
                        devices.isEmpty() -> stringResource(if (awareOn) R.string.discover_searching_aware else R.string.discover_searching)
                        else -> stringResource(R.string.discover_found, devices.size)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.discover_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            val shownError = error ?: fetchError
            if (shownError != null) InfoBanner(shownError, ChipTone.Warn, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BarqButton(stringResource(R.string.send_scan), onScanQr, kind = ButtonKind.Tonal, leading = BarqIcons.Qr)
            BarqButton(stringResource(R.string.broadcast_title), onBroadcast, kind = ButtonKind.Outlined, height = 52, leading = BarqIcons.ArrowUpRight)
        }
    }
}

@Composable
private fun Ring(size: Int, fill: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(fill).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun DeviceBubble(d: NearbyDevice, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.width(92.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(c.surface).border(if (busy) 2.dp else 1.dp, if (busy) c.primary else c.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(BarqIcons.Phone, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Text(d.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            if (d.address.startsWith("aware:")) stringResource(R.string.discover_aware)
            else stringResource(when { d.rssi > -60 -> R.string.discover_near; d.rssi > -78 -> R.string.discover_mid; else -> R.string.discover_far }),
            style = MaterialTheme.typography.labelSmall, color = if (d.address.startsWith("aware:")) c.primary else c.onSurfaceVariant,
        )
    }
}

/** -45 dBm sits on the inner ring (~80px), -90 dBm on the outer (~165px). */
private fun radiusFor(rssi: Int): Double {
    val t = ((rssi + 90).coerceIn(0, 45) / 45.0)
    return 165.0 - t * 85.0
}
