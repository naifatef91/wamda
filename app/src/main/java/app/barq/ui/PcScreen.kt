package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.IconButton
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
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = stop) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pc_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pc_subtitle), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

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
                Icon(BarqIcons.Warning, contentDescription = null, tint = x.warn, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pc_failed, s.message), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                BarqButton(stringResource(R.string.pc_retry), onClick = { PcSession.start(ctx) }, kind = ButtonKind.Tonal)
                Spacer(Modifier.height(10.dp))
                BarqButton(stringResource(R.string.pc_stop), onClick = stop, kind = ButtonKind.Outlined)
            }

            is PcState.Ready -> ReadyBody(s, shared.size, onPickFiles, stop)
        }
    }
}

@Composable
private fun ReadyBody(s: PcState.Ready, sharedCount: Int, onPickFiles: () -> Unit, onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val qr = remember(s.url) { qrBitmap(s.url, sizePx = 512) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PcConnectionCard(s)
        PcUsbCard(s.usbUrl)

        // Step 1: join the network
        BarqCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pc_step1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.pc_step1_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                CopyRow(label = stringResource(R.string.pc_ssid), value = s.ssid)
                CopyRow(label = stringResource(R.string.pc_password), value = s.pass)
                PcOptions(band = s.band)
            }
        }

        // Step 2: open the URL
        BarqCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pc_step2), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.pc_step2_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Text(
                    s.url,
                    style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = c.primary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Box(
                    Modifier.fillMaxWidth(0.6f).aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { Image(qr.asImageBitmap(), contentDescription = s.url, Modifier.fillMaxSize()) }
            }
        }

        // Stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PcStatTile(formatBytes(s.stats.bytesServed), stringResource(R.string.pc_stats_served), Modifier.weight(1f))
            PcStatTile(formatBytes(s.stats.bytesReceived), stringResource(R.string.pc_stats_received), Modifier.weight(1f), accent = true)
            PcStatTile(s.stats.lastClientAddress ?: "—", stringResource(R.string.pc_stats_client), Modifier.weight(1f), mono = true)
        }
        if (s.uploadedCount > 0) {
            PcReceivedList(s.uploadedNames)
        } else if (s.stats.lastClientAddress == null) {
            Text(stringResource(R.string.pc_stats_none), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }

        // Share + stop
        Spacer(Modifier.height(4.dp))
        BarqButton(
            if (sharedCount > 0) stringResource(R.string.pc_share_count, sharedCount) else stringResource(R.string.pc_share),
            onClick = onPickFiles, leading = BarqIcons.ArrowUp,
        )
        Text(stringResource(R.string.pc_shared_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        BarqButton(stringResource(R.string.pc_stop), onClick = onStop, kind = ButtonKind.Outlined)
        Spacer(Modifier.height(16.dp))
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
