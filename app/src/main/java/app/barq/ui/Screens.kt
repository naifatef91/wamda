package app.barq.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.Direction
import app.barq.R
import app.barq.TransferController
import app.barq.TransferState
import app.barq.data.HistoryStore
import app.barq.data.PickedFile
import app.barq.data.Settings
import app.barq.engine.QrPayload
import app.barq.engine.Snapshot
import app.barq.service.TransferService
import app.barq.ui.theme.extras
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// The home screen lives in Home.kt.

// ---------- Shared scaffold ----------

@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
}

// ---------- Receive ----------

@Composable
fun ReceiveScreen(onHome: () -> Unit) {
    val ctx = LocalContext.current
    val state by TransferController.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (state is TransferState.Idle) {
            TransferService.start(ctx)
            TransferController.startReceiving(ctx)
        }
    }
    BackHandler { onHome() }

    when (val s = state) {
        is TransferState.Idle, is TransferState.Preparing -> Screen {
            TopBar(stringResource(R.string.home_receive), onBack = onHome)
            Centered { Spinner(stringResource(R.string.receive_preparing)) }
        }
        is TransferState.Listening -> ReceiveQr(s.qr, s.band, onHome, dropped = s.dropped)
        is TransferState.AwaitingAccept -> {
            ReceiveQr(null, "", onHome)
            val m = s.manifest
            var trust by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { TransferController.accept(ctx, false) },
                title = { Text(stringResource(R.string.incoming_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.incoming_body, m.senderName, m.files.size, formatBytes(m.totalBytes)))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Checkbox(checked = trust, onCheckedChange = { trust = it })
                            Text(stringResource(R.string.incoming_trust), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { TransferController.accept(ctx, true, trust) }) { Text(stringResource(R.string.accept)) } },
                dismissButton = { TextButton(onClick = { TransferController.accept(ctx, false) }) { Text(stringResource(R.string.decline)) } },
            )
        }
        is TransferState.Running -> TransferView(s.direction, s.peer, s.band, s.snapshot, onHome)
        is TransferState.Finished -> FinishedView(s, onHome, onMore = { TransferController.continueReceiving() })
        is TransferState.Failed -> FailedView(s.message, s.retryable, onHome)
    }
}

@Composable
private fun ReceiveQr(qr: QrPayload?, band: String, onHome: () -> Unit, dropped: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val bmp = remember(qr) { qr?.let { qrBitmap(it.encode()) } }
    Screen {
        TopBar(stringResource(R.string.home_receive), onBack = onHome)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.receive_scan_hint), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            Box(
                Modifier.fillMaxWidth(0.76f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize()) else CircularProgressIndicator()
            }
            if (qr != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val good = band != "2.4GHz"
                    BarqChip(band, if (good) ChipTone.Ok else ChipTone.Warn, leading = BarqIcons.Wifi)
                    BarqChip(stringResource(R.string.chip_encrypted), ChipTone.Neutral, leading = BarqIcons.Lock)
                }
                BarqCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.receive_network_name), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        Text(qr.ssid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.receive_network_note), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                if (band == "2.4GHz") {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(x.warnSoft).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(BarqIcons.Warning, contentDescription = null, tint = x.warn, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.receive_band_warn), style = MaterialTheme.typography.bodySmall, color = x.warn)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (dropped) {
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(x.warnSoft).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = x.warn)
                    Text(stringResource(R.string.receive_dropped), style = MaterialTheme.typography.bodySmall, color = x.warn)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.primary))
                    Text(stringResource(R.string.receive_waiting), color = c.onSurfaceVariant)
                }
            }
            BarqButton(stringResource(R.string.transfer_cancel), onHome, kind = ButtonKind.Outlined, height = 52, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

// ---------- Send ----------

@Composable
fun SendScreen(onHome: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val state by TransferController.state.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var qr by remember { mutableStateOf<QrPayload?>(TransferController.openSendSession) }
    var scanError by remember { mutableStateOf(false) }

    // Files handed over by the system share sheet.
    val intake by TransferController.shareIntake.collectAsStateWithLifecycle()
    LaunchedEffect(intake) {
        if (intake.isNotEmpty()) { files = intake; TransferController.shareIntake.value = emptyList() }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents?.let { QrPayload.decode(it) }
        if (payload == null) { if (result.contents != null) scanError = true } else qr = payload
    }
    LaunchedEffect(qr, files) {
        val q = qr
        if (q != null && files.isNotEmpty() && state is TransferState.Idle) {
            TransferService.start(ctx)
            TransferController.startSending(ctx, q, files)
        }
    }

    val launchScan = {
        scanError = false
        scan.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setBeepEnabled(false); setOrientationLocked(false); setPrompt(""); setCaptureActivity(ScanActivity::class.java) })
    }

    when (val s = state) {
        // Step 1: pick files in-app. Step 2: choose the receiver (radar or QR).
        is TransferState.Idle -> if (files.isEmpty()) PickerScreen(onBack = onHome, onDone = { files = it })
        else DiscoverScreen(
            fileCount = files.size,
            onBack = { files = emptyList() },
            onScanQr = launchScan,
            onPicked = { qr = it },
        )
        is TransferState.Preparing -> Screen {
            TopBar(stringResource(R.string.home_send), onBack = onHome)
            Centered {
                Spinner(
                    when (s.step) {
                        TransferState.Step.WIFI -> stringResource(R.string.send_connecting_wifi)
                        TransferState.Step.PREPARE -> stringResource(R.string.send_preparing)
                        TransferState.Step.RECONNECT -> stringResource(R.string.send_reconnecting, s.attempt, s.maxAttempts)
                        else -> stringResource(R.string.send_handshake)
                    },
                )
            }
        }
        is TransferState.Running -> TransferView(s.direction, s.peer, s.band, s.snapshot, onHome)
        is TransferState.Finished -> FinishedView(s, onHome, onMore = { TransferController.prepareNextSend(); files = emptyList() })
        is TransferState.Failed -> FailedView(s.message, s.retryable, onHome)
        else -> Centered { Spinner("") }
    }
}

// ---------- Transfer ----------

@Composable
private fun TransferView(direction: Direction, peer: String, band: String, snap: Snapshot, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(snap.elapsedMs) {
        history.add((snap.speedBps / (1024 * 1024)).toFloat())
        if (history.size > 40) history.removeAt(0)
    }
    val mb = snap.speedBps / (1024 * 1024)
    val speedText = if (mb >= 10) "%.0f".format(mb) else "%.1f".format(mb)

    Screen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape), contentAlignment = Alignment.Center) {
                Icon(BarqIcons.Phone, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (direction == Direction.SEND) R.string.transfer_sending_to else R.string.transfer_receiving_from), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text(peer, style = MaterialTheme.typography.titleLarge)
            }
            BarqChip(stringResource(R.string.chip_encrypted), ChipTone.Ok, leading = BarqIcons.Lock)
        }

        Column(Modifier.padding(horizontal = 24.dp).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(speedText, style = MaterialTheme.typography.displayLarge, color = c.primary)
                Text("MB/s", style = MaterialTheme.typography.titleLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            }
            Sparkline(history)
        }

        Column(Modifier.padding(horizontal = 24.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThickProgress(snap.fraction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("${formatBytes(snap.bytesDone)} / ${formatBytes(snap.bytesTotal)}", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.transfer_eta, formatDuration(snap.etaSec)), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BarqChip(band, if (band == "2.4GHz") ChipTone.Warn else ChipTone.Ok)
                BarqChip(stringResource(R.string.transfer_streams, snap.streams), ChipTone.Accent)
                BarqChip("AES-256-GCM", ChipTone.Neutral)
            }
        }

        val doneCount = snap.files.count { it.skipped || it.done >= it.size }
        Box(Modifier.padding(horizontal = 24.dp).padding(top = 22.dp, bottom = 8.dp)) {
            SectionLabel(stringResource(R.string.transfer_files), "$doneCount / ${snap.files.size}")
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(snap.files, key = { it.id }) { f ->
                val done = !f.skipped && f.size > 0 && f.done >= f.size
                val frac = if (f.size == 0L) 1f else (f.done.toDouble() / f.size).toFloat()
                val trailing = when {
                    f.skipped -> stringResource(R.string.transfer_skipped)
                    done -> formatBytes(f.size)
                    else -> "${formatBytes(f.size)} · ${(frac * 100).toInt()}%"
                }
                FileRow(f.name, trailing, frac, iconFor(f.name), done, f.skipped)
            }
        }
        Box(Modifier.padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
            BarqButton(stringResource(R.string.transfer_stop), onCancel, kind = ButtonKind.Danger, height = 52)
        }
    }
}

private fun iconFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "heic", "webp", "gif" -> BarqIcons.Image
    "mp4", "mkv", "mov", "avi", "webm" -> BarqIcons.Video
    else -> BarqIcons.File
}

// ---------- Finished / Failed ----------

@Composable
private fun FinishedView(s: TransferState.Finished, onHome: () -> Unit, onMore: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val skipped = s.snapshot.files.count { it.skipped }
    Screen {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Box(Modifier.size(96.dp).clip(CircleShape).background(x.okSoft), contentAlignment = Alignment.Center) {
                Icon(BarqIcons.Check, contentDescription = null, tint = x.ok, modifier = Modifier.size(46.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.transfer_done), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.transfer_summary, formatBytes(s.snapshot.bytesDone), formatDuration(s.snapshot.elapsedMs / 1000), formatSpeed(s.snapshot.avgBps)),
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(s.snapshot.files.size.toString(), stringResource(R.string.stat_files), Modifier.weight(1f))
                Stat(skipped.toString(), stringResource(R.string.stat_skipped), Modifier.weight(1f))
                Stat(formatSpeed(s.snapshot.avgBps), stringResource(R.string.stat_avg), Modifier.weight(1f), tint = c.primary)
            }
            if (s.incomplete > 0) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.transfer_incomplete, s.incomplete), color = c.error)
            }
            val p = s.snapshot.profileMs
            if (p.isNotEmpty() && s.snapshot.streams > 0) {
                // Stage times are summed across streams; divide by streams for wall-clock-ish seconds.
                val n = s.snapshot.streams
                fun sec(k: String) = "%.1f".format((p[k] ?: 0L) / 1000.0 / n)
                val line = if (s.direction == Direction.SEND)
                    stringResource(R.string.profile_send, sec("read"), sec("net"), n)
                else stringResource(R.string.profile_receive, sec("net"), sec("write"), n)
                Spacer(Modifier.height(12.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            if (s.sessionOpen && onMore != null) {
                Text(stringResource(R.string.session_open, s.peer), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                BarqButton(stringResource(if (s.direction == Direction.SEND) R.string.send_more else R.string.receive_more), onMore)
                Spacer(Modifier.height(10.dp))
                BarqButton(stringResource(R.string.back_home), onHome, kind = ButtonKind.Outlined, height = 52)
            } else {
                BarqButton(stringResource(R.string.back_home), onHome)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    BarqCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = tint, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailedView(message: String, retryable: Boolean, onHome: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Screen {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.errorContainer), contentAlignment = Alignment.Center) {
                Icon(BarqIcons.Warning, contentDescription = null, tint = c.error, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.transfer_failed), style = MaterialTheme.typography.headlineMedium, color = c.error)
            Spacer(Modifier.height(8.dp))
            Text(message, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            if (retryable) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.failed_kept), color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            if (retryable) {
                BarqButton(stringResource(R.string.retry), { TransferController.retry(ctx) })
                Spacer(Modifier.height(10.dp))
                BarqButton(stringResource(R.string.back_home), onHome, kind = ButtonKind.Outlined, height = 52)
            } else {
                BarqButton(stringResource(R.string.back_home), onHome)
            }
        }
    }
}

// ---------- Small helpers ----------

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Spinner(label: String) {
    CircularProgressIndicator()
    if (label.isNotEmpty()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
