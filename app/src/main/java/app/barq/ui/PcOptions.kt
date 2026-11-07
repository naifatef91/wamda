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
fun PcConnectionCard(s: app.barq.web.PcState.Ready) {
    val c = MaterialTheme.colorScheme
    val x = app.barq.ui.theme.LocalBarqExtras.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val connected = s.stats.lastSeenMs > 0 && now - s.stats.lastSeenMs < 8_000
    BarqCard(Modifier.fillMaxWidth(), tint = if (connected) x.okSoft else null) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(if (connected) BarqIcons.Check else BarqIcons.Wifi, if (connected) x.ok else c.primaryContainer, if (connected) c.surface else c.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) stringResource(R.string.pc_connected, s.stats.lastClientAddress ?: "") else stringResource(R.string.pc_waiting),
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

/** USB-C to the computer: same page over USB tethering; shows the link when tethering is on, else how to turn it on. */
@Composable
fun PcUsbCard(usbUrl: String?) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val x = app.barq.ui.theme.LocalBarqExtras.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BarqCard(Modifier.fillMaxWidth(), tint = if (usbUrl != null) x.okSoft else null) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(BarqIcons.Next, if (usbUrl != null) x.ok else c.primaryContainer, if (usbUrl != null) c.surface else c.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pc_usb_title), style = MaterialTheme.typography.titleMedium, color = if (usbUrl != null) x.ok else c.onSurface)
                    Text(
                        stringResource(if (usbUrl != null) R.string.pc_usb_on else R.string.pc_usb_off),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
            }
            if (usbUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(usbUrl, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = c.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(usbUrl)) }) { Text(stringResource(R.string.pc_copy)) }
                }
            } else {
                Text(stringResource(R.string.pc_usb_steps), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                TextButton(onClick = {
                    val intents = listOf(
                        android.content.Intent("android.settings.TETHER_SETTINGS"),
                        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
                    )
                    for (i in intents) { if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break }
                }) { Text(stringResource(R.string.pc_usb_open_settings)) }
            }
        }
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
