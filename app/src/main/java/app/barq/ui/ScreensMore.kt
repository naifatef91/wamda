package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.Direction
import app.barq.R
import app.barq.data.HistoryStore
import app.barq.data.Settings
import app.barq.data.ThemeChoice
import app.barq.data.TransferRecord
import app.barq.ui.theme.extras
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ---------- History ----------

@Composable
fun HistoryScreen(onBack: () -> Unit, onReceive: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val records by HistoryStore.get(ctx).records.collectAsStateWithLifecycle()
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (records.isNotEmpty()) TextButton(onClick = { HistoryStore.get(ctx).clear() }) { Text(stringResource(R.string.history_clear), color = c.error) }
        }

        if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(BarqIcons.Bolt, contentDescription = null, tint = c.outline, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.history_empty), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            return
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatBytes(records.sumOf { it.bytes }), stringResource(R.string.history_total), Modifier.weight(1f))
            StatTile(records.size.toString(), stringResource(R.string.history_count), Modifier.weight(1f))
            StatTile(formatSpeed(records.maxOf { it.avgBps }), stringResource(R.string.history_best), Modifier.weight(1f), accent = true)
        }

        val grouped = records.groupBy { dayLabel(ctx, it.startedAt) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (day, items) ->
                item(key = "day-$day") {
                    Text(day, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp))
                }
                items(items, key = { it.id }) { r -> HistoryRow(r, onReceive) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val c = MaterialTheme.colorScheme
    BarqCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = if (accent) c.primary else c.onSurface, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
fun HistoryRow(r: TransferRecord, onResume: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val incoming = r.direction == Direction.RECEIVE
    val tint = if (!r.complete) x.warnSoft else null
    BarqCard(Modifier.fillMaxWidth(), tint = tint) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                if (incoming) BarqIcons.ArrowDown else BarqIcons.ArrowUp,
                bg = when { !r.complete -> x.warnSoft; incoming -> x.okSoft; else -> c.primaryContainer },
                fg = when { !r.complete -> x.warn; incoming -> x.ok; else -> c.primary },
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (incoming) R.string.history_from else R.string.history_to, r.peer), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    if (r.complete) stringResource(R.string.history_line, r.fileCount, formatBytes(r.bytes), timeLabel(r.startedAt))
                    else stringResource(R.string.history_incomplete, r.incomplete, formatBytes(r.bytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.complete) c.onSurfaceVariant else x.warn,
                )
            }
            if (!r.complete && incoming && onResume != null) {
                Text(
                    stringResource(R.string.history_resume),
                    style = MaterialTheme.typography.labelMedium, color = c.onSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(x.warn).clickable(onClick = onResume).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Text(formatSpeed(r.avgBps), style = MaterialTheme.typography.labelMedium, color = if (incoming) x.ok else c.primary)
            }
        }
    }
}

private fun dayLabel(ctx: android.content.Context, at: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return ctx.getString(R.string.day_today)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return ctx.getString(R.string.day_yesterday)
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
}

private fun timeLabel(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

// ---------- Settings ----------

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val settings = Settings.get(ctx)
    val s by settings.state.collectAsStateWithLifecycle()
    var editingName by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    if (editingName) {
        DeviceNameDialog(
            current = s.deviceName,
            onDismiss = { editingName = false },
            onSave = { newName ->
                Settings.get(ctx).update { it.copy(deviceName = newName.trim().ifBlank { it.deviceName }) }
                editingName = false
            },
        )
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
            item { SectionHeader(stringResource(R.string.settings_device)) }
            item {
                BarqCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingRow(stringResource(R.string.settings_name), stringResource(R.string.settings_name_hint), onClick = { editingName = true }) {
                            Text(
                                s.deviceName,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp),
                            )
                            Icon(EditIcon, contentDescription = stringResource(R.string.settings_name_edit), tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        HorizontalDivider(color = c.outline)
                        SettingRow(stringResource(R.string.settings_folder), "Download/Wamda", mono = true) {}
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_transfer)) }
            item {
                BarqCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingRow(stringResource(R.string.settings_auto_accept), stringResource(R.string.settings_auto_accept_hint)) {
                            BarqSwitch(s.autoAcceptTrusted) { v -> settings.update { it.copy(autoAcceptTrusted = v) } }
                        }
                        HorizontalDivider(color = c.outline)
                        SettingRow(stringResource(R.string.settings_skip), stringResource(R.string.settings_skip_hint)) {
                            BarqSwitch(s.skipExisting) { v -> settings.update { it.copy(skipExisting = v) } }
                        }
                        HorizontalDivider(color = c.outline)
                        SettingRow(stringResource(R.string.settings_5ghz), stringResource(R.string.settings_5ghz_hint)) {
                            BarqSwitch(s.prefer5GHz) { v -> settings.update { it.copy(prefer5GHz = v) } }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_trusted)) }
            item {
                BarqCard(Modifier.fillMaxWidth()) {
                    Column {
                        if (s.trustedDevices.isEmpty()) {
                            Text(stringResource(R.string.settings_trusted_empty), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                        }
                        s.trustedDevices.forEachIndexed { i, d ->
                            if (i > 0) HorizontalDivider(color = c.outline)
                            SettingRow(d, null) {
                                TextButton(onClick = { settings.untrust(d) }) { Text(stringResource(R.string.settings_remove), color = c.error) }
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                BarqCard(Modifier.fillMaxWidth()) {
                    SettingRow(stringResource(R.string.settings_theme), null) {
                        Segmented(
                            options = listOf(ThemeChoice.SYSTEM to stringResource(R.string.theme_system), ThemeChoice.LIGHT to stringResource(R.string.theme_light), ThemeChoice.DARK to stringResource(R.string.theme_dark)),
                            selected = s.theme,
                        ) { v -> settings.update { it.copy(theme = v) } }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_about_section)) }
            item {
                BarqCard(Modifier.fillMaxWidth()) {
                    Column {
                        LinkRow(stringResource(R.string.settings_about_app)) { onOpen("about") }
                        HorizontalDivider(color = c.outline)
                        LinkRow(stringResource(R.string.settings_privacy)) { onOpen("privacy") }
                        HorizontalDivider(color = c.outline)
                        LinkRow(stringResource(R.string.settings_terms)) { onOpen("terms") }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        }
    }
}

/** Edit the device name in a dialog: pre-filled, text selected, Save / Cancel. */
@Composable
private fun DeviceNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_name)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A navigation row: title plus a trailing chevron. */
@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    SettingRow(title, null, onClick = onClick) {
        Icon(BarqIcons.Next, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Small pencil glyph for the editable name row (kept local so Icons.kt stays untouched). */
private val EditIcon: ImageVector by lazy {
    ImageVector.Builder(name = "edit", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 20f); horizontalLineTo(8f); lineTo(18.5f, 9.5f); lineTo(14.5f, 5.5f); lineTo(4f, 16f); close()
            moveTo(12.5f, 7.5f); lineTo(16.5f, 11.5f)
        }
    }.build()
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String?, mono: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val base = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Row(base.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        trailing()
    }
}

@Composable
private fun BarqSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.outline, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.outline),
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.background).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.surface else c.background).clickable { onSelect(value) }.padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.onSurface else c.onSurfaceVariant) }
        }
    }
}
