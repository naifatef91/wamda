package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.R
import app.barq.data.PickedFile
import app.barq.ui.theme.extras
import app.barq.web.PcSession
import app.barq.web.PcState
import kotlinx.coroutines.delay

/**
 * PC mode: the phone hosts a Wi-Fi Direct network and a web page; the computer joins and
 * exchanges files through its browser. [shared] is the current list the user picked; the
 * screen pushes it into [PcSession] whenever it changes.
 */
@Composable
fun PcScreen(onBack: () -> Unit, onPickFiles: () -> Unit, shared: List<PickedFile>) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val state by PcSession.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { PcSession.start(ctx) }
    LaunchedEffect(shared) { PcSession.setShared(shared) }

    val stop = { PcSession.stop(); onBack() }
    BackHandler { stop() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(stringResource(R.string.pc_title), subtitle = stringResource(R.string.pc_subtitle), onBack = stop)

        when (val s = state) {
            is PcState.Idle, is PcState.Starting -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pc_starting), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }

            is PcState.Failed -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(BarqIcons.Warning, contentDescription = null, tint = c.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pc_failed, s.message), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                BarqButton(stringResource(R.string.pc_retry), onClick = { PcSession.start(ctx) }, kind = ButtonKind.Tonal, height = 52)
                Spacer(Modifier.height(10.dp))
                BarqButton(stringResource(R.string.pc_stop), onClick = stop, kind = ButtonKind.Outlined, height = 52)
            }

            is PcState.Ready -> ReadyBody(s, shared.size, onPickFiles, stop)
        }
    }
}

@Composable
private fun ReadyBody(s: PcState.Ready, sharedCount: Int, onPickFiles: () -> Unit, onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    // Which way the computer connects: 0 = Wi-Fi (always the starting choice), 1 = USB cable.
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PcConnectionCard(s.stats.lastSeenMs, s.stats.lastClientAddress)

        PcTabs(tab, listOf(stringResource(R.string.pc_tab_wifi), stringResource(R.string.pc_usb_title))) { tab = it }

        if (tab == 0) WifiPanel(s.ssid, s.pass, s.band, s.url) else PcUsbCard(s.usb)

        // What is happening now
        Text(stringResource(R.string.pc_activity), style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        LiveStats(s.stats.bytesServed, s.stats.bytesReceived, s.stats.lastClientAddress)
        if (s.uploadedCount > 0) {
            PcReceivedList(s.uploadedNames)
        } else if (s.stats.lastClientAddress == null) {
            Text(stringResource(R.string.pc_stats_none), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }

        // Share + stop
        Spacer(Modifier.height(2.dp))
        BarqButton(
            if (sharedCount > 0) stringResource(R.string.pc_share_count, sharedCount) else stringResource(R.string.pc_share),
            onClick = onPickFiles, leading = BarqIcons.ArrowUp,
        )
        Text(stringResource(R.string.pc_shared_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.pc_stop), color = c.error) }
    }
}

/** Two-way switch between the connection methods. */
@Composable
private fun PcTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceVariant.copy(alpha = 0.5f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) c.primary else c.onSurfaceVariant, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Wi-Fi route: join the network, open the address. Takes plain values so it only redraws when they change. */
@Composable
private fun WifiPanel(ssid: String, pass: String, band: String, url: String) {
    val c = MaterialTheme.colorScheme
    val qr = remember(url) { qrBitmap(url, sizePx = 512) }
    var options by rememberSaveable { mutableStateOf(false) }

    BarqCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.pc_step1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pc_step1_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            CopyRow(label = stringResource(R.string.pc_ssid), value = ssid)
            CopyRow(label = stringResource(R.string.pc_password), value = pass)
            TextButton(onClick = { options = !options }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(stringResource(R.string.pc_options), color = c.primary)
            }
            if (options) PcOptions(band = band)
        }
    }

    BarqCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pc_step2), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = c.primary)
                Text(stringResource(R.string.pc_step2_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Box(
                Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) { Image(qr.asImageBitmap(), contentDescription = url, Modifier.fillMaxSize()) }
        }
    }
}

/** The three live numbers; separate from the rest so a transfer only redraws this row. */
@Composable
private fun LiveStats(served: Long, received: Long, client: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PcStatTile(formatBytes(served), stringResource(R.string.pc_stats_served), Modifier.weight(1f))
        PcStatTile(formatBytes(received), stringResource(R.string.pc_stats_received), Modifier.weight(1f), accent = true)
        PcStatTile(client ?: "—", stringResource(R.string.pc_stats_client), Modifier.weight(1f), mono = true)
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.background).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }) {
            Text(stringResource(if (copied) R.string.pc_copied else R.string.pc_copy), color = c.primary)
        }
    }
}

@Composable
private fun PcStatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false, mono: Boolean = false) {
    val c = MaterialTheme.colorScheme
    BarqCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value, style = if (mono) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                color = if (accent) c.primary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
