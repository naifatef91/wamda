package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.R
import app.barq.TransferController
import app.barq.TransferState
import app.barq.net.usb.UsbSession
import app.barq.net.usb.UsbState
import app.barq.ui.theme.extras

/** USB-C cable mode: detect the other phone, then send or receive over the cable. */
@Composable
fun CableScreen(onBack: () -> Unit, onPickFiles: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val usb by UsbSession.state.collectAsStateWithLifecycle()
    val state by TransferController.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TransferController.attachUsb(ctx) }
    BackHandler { onBack() }

    when (val s = state) {
        is TransferState.Running -> { TransferView(s.direction, s.peer, s.band, s.snapshot, onBack); return }
        is TransferState.Finished -> { FinishedView(s, onBack); return }
        is TransferState.Failed -> { FailedView(s.message, false, onBack); return }
        is TransferState.Preparing -> {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
                    Text(stringResource(R.string.cable_title), style = MaterialTheme.typography.headlineSmall)
                }
                Centered { Spinner(stringResource(if (s.step == TransferState.Step.PREPARE) R.string.send_preparing else R.string.send_handshake)) }
            }
            return
        }
        else -> {}
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Text(stringResource(R.string.cable_title), style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val u = usb) {
                is UsbState.Connected -> {
                    BarqCard(Modifier.fillMaxWidth(), tint = x.okSoft) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(BarqIcons.Check, x.ok, c.surface)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.cable_connected, u.peerName), style = MaterialTheme.typography.titleMedium, color = x.ok)
                                Text(stringResource(if (u.role == "host") R.string.cable_role_host else R.string.cable_role_accessory), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    BarqButton(stringResource(R.string.cable_send), onPickFiles, leading = BarqIcons.ArrowUp)
                    Text(stringResource(R.string.cable_waiting_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    BarqCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cable_multipath_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                    }
                }
                is UsbState.Failed -> {
                    BarqCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.cable_failed), style = MaterialTheme.typography.titleMedium, color = c.error)
                            Text(u.message, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                    BarqButton(stringResource(R.string.retry), { UsbSession.stop(); UsbSession.start(ctx) }, kind = ButtonKind.Tonal, height = 52)
                }
                else -> {
                    val hint = (u as? UsbState.Searching)?.hint
                    BarqCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.height(22.dp).fillMaxWidth(0.08f), strokeWidth = 2.dp)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (hint == "switching") R.string.cable_switching else R.string.cable_searching), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.cable_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    BarqCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.cable_steps_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.cable_steps), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                    BarqButton(stringResource(R.string.retry), { UsbSession.start(ctx) }, kind = ButtonKind.Tonal, height = 52)
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }

    // Incoming cable transfer: same acceptance dialog as Wi-Fi.
    val awaiting = state as? TransferState.AwaitingAccept
    if (awaiting != null) {
        var trust by remember { mutableStateOf(false) }
        val m = awaiting.manifest
        AlertDialog(
            onDismissRequest = { TransferController.accept(ctx, false) },
            title = { Text(stringResource(R.string.incoming_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.incoming_body, m.senderName, m.files.size, formatBytes(m.totalBytes)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it })
                        Text(stringResource(R.string.incoming_trust), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { TransferController.accept(ctx, true, trust) }) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = { TransferController.accept(ctx, false) }) { Text(stringResource(R.string.decline)) } },
        )
    }
}
