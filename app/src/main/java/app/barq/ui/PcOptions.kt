package app.barq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.R
import app.barq.data.Settings
import app.barq.web.PcSession

/** Live link status: the browser polls every 2 s, so a request in the last 8 s means the computer is connected. */
@Composable
fun PcConnectionCard(lastSeenMs: Long, lastClient: String?) {
    val c = MaterialTheme.colorScheme
    val x = app.barq.ui.theme.LocalBarqExtras.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val connected = lastSeenMs > 0 && now - lastSeenMs < 8_000
    BarqCard(Modifier.fillMaxWidth(), tint = if (connected) x.okSoft else null) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(if (connected) BarqIcons.Check else BarqIcons.Wifi, if (connected) x.ok else c.primaryContainer, if (connected) c.surface else c.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) stringResource(R.string.pc_connected, lastClient ?: "") else stringResource(R.string.pc_waiting),
                    style = MaterialTheme.typography.titleMedium, color = if (connected) x.ok else c.onSurface,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (connected) R.string.pc_connected_hint else R.string.pc_waiting_hint),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
        }
    }
}

/** Files the computer uploaded during this session, newest first. */
@Composable
fun PcReceivedList(names: List<String>) {
    if (names.isEmpty()) return
    val c = MaterialTheme.colorScheme
    BarqCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pc_received_title, names.size), style = MaterialTheme.typography.titleMedium)
            names.asReversed().take(6).forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(BarqIcons.ArrowDown, c.primaryContainer, c.primary, size = 30)
                    Text(n, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Text(stringResource(R.string.pc_received_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

/**
 * USB-C to the computer: the same page over USB tethering. Walks the user through the three things that
 * have to happen (cable, file-transfer mode, tethering switch), highlights the first one still open from
 * what the phone can see, and shows the link and its QR code as soon as the tethering interface has an address.
 */
@Composable
fun PcUsbCard(usb: app.barq.web.UsbTether.Status) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val x = app.barq.ui.theme.LocalBarqExtras.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val stage = usb.stage
    val ready = stage == app.barq.web.UsbTether.Stage.READY
    val settingsFailed = stringResource(R.string.pc_usb_settings_failed)
    BarqCard(Modifier.fillMaxWidth(), tint = if (ready) x.okSoft else null) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(BarqIcons.Usb, if (ready) x.ok else c.primaryContainer, if (ready) c.surface else c.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pc_usb_title), style = MaterialTheme.typography.titleMedium, color = if (ready) x.ok else c.onSurface)
                    Text(
                        stringResource(
                            when {
                                ready -> R.string.pc_usb_ready_hint
                                stage == app.barq.web.UsbTether.Stage.STARTING -> R.string.pc_usb_state_starting
                                stage == app.barq.web.UsbTether.Stage.CABLE_ONLY && usb.fileTransfer -> R.string.pc_usb_state_files
                                stage == app.barq.web.UsbTether.Stage.CABLE_ONLY -> R.string.pc_usb_state_cable
                                else -> R.string.pc_usb_state_none
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
                usb.driver?.takeIf { ready }?.let { BarqChip(it, ChipTone.Ok) }
            }
            val url = usb.url
            if (ready && url != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = c.primary, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(url)) }) { Text(stringResource(R.string.pc_copy)) }
                }
                val qr = remember(url) { qrBitmap(url, sizePx = 360) }
                androidx.compose.foundation.Image(
                    bitmap = qr.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(168.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                val done1 = stage != app.barq.web.UsbTether.Stage.NO_CABLE
                val done2 = usb.fileTransfer || stage == app.barq.web.UsbTether.Stage.STARTING
                val current = when { !done1 -> 1; !done2 -> 2; else -> 3 }
                UsbStep(1, stringResource(R.string.pc_usb_step_cable), done1, current == 1)
                UsbStep(2, stringResource(R.string.pc_usb_step_mode), done2, current == 2)
                UsbStep(3, stringResource(R.string.pc_usb_step_tether), false, current == 3)
                TextButton(onClick = {
                    if (!app.barq.web.UsbTether.openTetherSettings(ctx)) android.widget.Toast.makeText(ctx, settingsFailed, android.widget.Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.pc_usb_open_settings)) }
                Text(stringResource(R.string.pc_usb_windows), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsbStep(n: Int, text: String, done: Boolean, current: Boolean) {
    val c = MaterialTheme.colorScheme
    val x = app.barq.ui.theme.LocalBarqExtras.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (done) x.ok else if (current) c.primary else c.outline.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(BarqIcons.Check, contentDescription = null, tint = c.surface, modifier = Modifier.size(14.dp))
            else Text(n.toString(), style = MaterialTheme.typography.labelMedium, color = if (current) c.onPrimary else c.onSurfaceVariant)
        }
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = if (current) c.onSurface else c.onSurfaceVariant,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Band chip, 5GHz switch and the password editor for PC mode; any change restarts the group. */
@Composable
fun PcOptions(band: String) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val settings = Settings.get(ctx)
    val prefs by settings.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarqChip(band, if (band == "2.4GHz") ChipTone.Warn else ChipTone.Ok, leading = BarqIcons.Wifi)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pc_use_5ghz), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.pc_use_5ghz_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            BarqSwitch(prefs.pc5GHz) { on -> settings.update { it.copy(pc5GHz = on) }; PcSession.restart(ctx) }
        }
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.pc_change_password)) }
    }

    if (editing) {
        var value by remember { mutableStateOf(prefs.pcPassword) }
        val valid = value.length in 8..63 && value.all { it.code in 32..126 }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.pc_change_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, singleLine = true,
                        label = { Text(stringResource(R.string.pc_password)) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        isError = value.isNotEmpty() && !valid,
                    )
                    Text(stringResource(R.string.pc_password_rule), style = MaterialTheme.typography.bodySmall, color = if (valid || value.isEmpty()) c.onSurfaceVariant else c.error)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    settings.update { it.copy(pcPassword = value) }
                    editing = false
                    PcSession.restart(ctx)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
