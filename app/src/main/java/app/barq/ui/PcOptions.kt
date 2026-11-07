package app.barq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
            BarqChip(band, if (band == "2.4GHz") ChipTone.Neutral else ChipTone.Ok, leading = BarqIcons.Wifi)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pc_use_5ghz), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.pc_use_5ghz_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Switch(checked = prefs.pc5GHz, onCheckedChange = { on -> settings.update { it.copy(pc5GHz = on) }; PcSession.restart(ctx) })
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
