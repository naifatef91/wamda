package app.barq.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.Direction
import app.barq.R
import app.barq.TransferController
import app.barq.TransferState
import app.barq.data.HistoryStore
import app.barq.data.Settings
import app.barq.net.BleScanner
import app.barq.net.NearbyDevice
import app.barq.ui.theme.extras
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/** The radar card keeps the mockup's deep-navy look in both themes. */
private val RadarBg = Color(0xFF0E1A21)
private val RadarRing = Color(0xFF203037)
private val RadarInk = Color(0xFFE8F0F0)
private val RadarMuted = Color(0xFF8FA1A6)

@Composable
fun HomeScreen(
    onSend: () -> Unit, onReceive: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
    onOpenTransfer: () -> Unit = {}, onPc: () -> Unit = {}, onCable: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val records by HistoryStore.get(ctx).records.collectAsStateWithLifecycle()
    val prefs by Settings.get(ctx).state.collectAsStateWithLifecycle()
    val state by TransferController.state.collectAsStateWithLifecycle()

    // Nearby phones running the app, so the radar on the home screen is live.
    val scanner = remember { BleScanner(ctx) }
    val nearby by scanner.devices.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        runCatching { scanner.start() }
        onDispose { runCatching { scanner.stop() } }
    }
    LaunchedEffect(Unit) { while (isActive) { delay(2000); scanner.prune() } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
    ) {
        // Header: settings at the start edge, app name in the middle, avatar at the end.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                Icon(BarqIcons.Settings, contentDescription = stringResource(R.string.settings_title), tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.primaryContainer).border(1.dp, c.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(prefs.deviceName.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.home_hello, prefs.deviceName), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            BarqChip(stringResource(R.string.home_secure), ChipTone.Ok, leading = BarqIcons.Check, modifier = Modifier.padding(bottom = 6.dp))
        }

        val running = state as? TransferState.Running
        if (running != null) {
            Spacer(Modifier.height(14.dp))
            LiveTransferBar(running, onOpenTransfer)
        }

        Spacer(Modifier.height(18.dp))
        RadarCard(nearby = nearby, onClick = onSend)

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard(
                title = stringResource(R.string.home_send), hint = stringResource(R.string.home_send_hint2),
                icon = BarqIcons.ArrowUpRight, bg = c.primary, fg = c.onPrimary, onClick = onSend, modifier = Modifier.weight(1f),
            )
            ActionCard(
                title = stringResource(R.string.home_receive), hint = stringResource(R.string.home_receive_hint2),
                icon = BarqIcons.ArrowDownLeft, bg = c.surface, fg = c.onSurface, onClick = onReceive, modifier = Modifier.weight(1f), outlined = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarqButton(stringResource(R.string.pc_title), onPc, kind = ButtonKind.Outlined, height = 50, leading = BarqIcons.Wifi, modifier = Modifier.weight(1f))
            BarqButton(stringResource(R.string.cable_title), onCable, kind = ButtonKind.Outlined, height = 50, leading = BarqIcons.Next, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(26.dp))
        Text(stringResource(R.string.home_activity), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.home_transfers), style = MaterialTheme.typography.titleLarge)
            if (records.isNotEmpty()) {
                Text(
                    stringResource(R.string.home_view_all), style = MaterialTheme.typography.labelMedium, color = c.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHistory).padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (records.isEmpty()) {
            BarqCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_recent_empty), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(14.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { records.take(3).forEach { HistoryRow(it) } }
        }
    }
}

/** Deep-navy card with a slowly sweeping radar, the scan target in the middle and nearby phones around it. */
@Composable
private fun RadarCard(nearby: List<NearbyDevice>, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = c.primary
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(RadarBg).clickable(role = Role.Button, onClick = onClick).padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_ready), style = MaterialTheme.typography.titleMedium, color = RadarInk, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.home_ready_sub), style = MaterialTheme.typography.bodySmall, color = RadarMuted)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        }

        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxR = size.height / 2
                listOf(0.35f, 0.65f, 0.95f).forEach { f ->
                    drawCircle(RadarRing, radius = maxR * f, center = center, style = Stroke(1.dp.toPx()))
                }
                rotate(sweep, center) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.22f)), center),
                        startAngle = -70f, sweepAngle = 70f, useCenter = true,
                        topLeft = Offset(center.x - maxR * 0.95f, center.y - maxR * 0.95f),
                        size = androidx.compose.ui.geometry.Size(maxR * 1.9f, maxR * 1.9f),
                    )
                }
            }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(accent).border(8.dp, accent.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(BarqIcons.Qr, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(28.dp)) }

            val shown = nearby.take(4)
            shown.forEachIndexed { i, d ->
                val angle = Math.toRadians(-60.0 + i * (360.0 / shown.size.coerceAtLeast(1)))
                val r = 62.0 + 24.0 * ((d.rssi + 90).coerceIn(0, 45) / 45.0).let { 1 - it }
                Box(
                    Modifier.offset(x = (r * cos(angle)).dp, y = (r * sin(angle)).dp).size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(RadarRing).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(BarqIcons.Phone, contentDescription = d.name, tint = accent, modifier = Modifier.size(18.dp)) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(RadarRing), contentAlignment = Alignment.Center) {
                Icon(BarqIcons.Phone, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Text(
                if (nearby.isEmpty()) stringResource(R.string.home_nearby_none) else stringResource(R.string.home_nearby_count, nearby.size),
                style = MaterialTheme.typography.bodyMedium, color = RadarInk, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.chip_encrypted), style = MaterialTheme.typography.labelSmall, color = RadarMuted)
        }
    }
}

@Composable
private fun ActionCard(
    title: String, hint: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, outlined: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.height(118.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .then(if (outlined) Modifier.border(1.dp, c.outline, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(fg.copy(alpha = if (outlined) 0.08f else 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp)) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact strip under the header while a transfer runs: who, how much, how long, how fast. */
@Composable
private fun LiveTransferBar(s: TransferState.Running, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val snap = s.snapshot
    val send = s.direction == Direction.SEND
    BarqCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.live_open), onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(if (send) BarqIcons.ArrowUp else BarqIcons.ArrowDown, c.primaryContainer, c.primary, size = 36)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (send) R.string.live_sending_to else R.string.live_receiving_from, s.peer),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(snap.bytesDone)} / ${formatBytes(snap.bytesTotal)} · ${stringResource(R.string.live_remaining, formatDuration(snap.etaSec))}",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatSpeed(snap.speedBps), style = MaterialTheme.typography.titleLarge, color = c.primary, maxLines = 1)
                    Text(stringResource(R.string.live_speed), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
            ThinProgress(snap.fraction)
        }
    }
}
