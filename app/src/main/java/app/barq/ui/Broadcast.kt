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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.barq.PeerProgress
import app.barq.PeerStatus
import app.barq.R
import app.barq.TransferState
import app.barq.ui.theme.extras

/** Sender side of broadcast mode: one QR for everyone, one progress row per receiver. */
@Composable
fun BroadcastScreen(s: TransferState.Broadcasting, fileCount: Int, onFinish: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val bmp = remember(s.qr) { qrBitmap(s.qr.encode(), sizePx = 512) }
    BackHandler { onFinish() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFinish) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.broadcast_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.send_picked_count, fileCount), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            BarqChip(s.band, if (s.band == "2.4GHz") ChipTone.Warn else ChipTone.Ok, leading = BarqIcons.Wifi)
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth(0.56f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Image(bmp.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize()) }
                    Text(stringResource(R.string.broadcast_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionLabel(
                    stringResource(R.string.broadcast_peers, s.peers.size),
                    "${s.peers.count { it.status == PeerStatus.DONE }} ✓",
                )
            }
            if (s.peers.isEmpty()) {
                item {
                    BarqCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c.primary))
                            Text(stringResource(R.string.broadcast_none), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            items(s.peers, key = { it.session }) { p -> PeerRow(p) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp, top = 8.dp)) {
            BarqButton(stringResource(R.string.broadcast_finish), onFinish, kind = ButtonKind.Outlined, height = 52)
        }
    }
}

@Composable
private fun PeerRow(p: PeerProgress) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val snap = p.snapshot
    val (badgeBg, badgeFg) = when (p.status) {
        PeerStatus.DONE -> x.okSoft to x.ok
        PeerStatus.FAILED -> c.errorContainer to c.error
        else -> c.primaryContainer to c.primary
    }
    BarqCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(if (p.status == PeerStatus.DONE) BarqIcons.Check else BarqIcons.Phone, badgeBg, badgeFg, size = 36)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (p.status) {
                            PeerStatus.JOINED -> stringResource(R.string.peer_joined)
                            PeerStatus.RUNNING -> if (snap != null) "${formatBytes(snap.bytesDone)} / ${formatBytes(snap.bytesTotal)} · ${stringResource(R.string.transfer_eta, formatDuration(snap.etaSec))}" else stringResource(R.string.peer_joined)
                            PeerStatus.DONE -> if (snap != null) stringResource(R.string.peer_done, formatBytes(snap.bytesDone), formatSpeed(snap.avgBps)) else stringResource(R.string.transfer_done)
                            PeerStatus.FAILED -> p.message.ifBlank { stringResource(R.string.transfer_failed) }
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (p.status == PeerStatus.FAILED) c.error else c.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (p.status == PeerStatus.RUNNING && snap != null) {
                    Text(formatSpeed(snap.speedBps), style = MaterialTheme.typography.titleMedium, color = c.primary)
                }
            }
            ThinProgress(
                when (p.status) { PeerStatus.DONE -> 1f; PeerStatus.RUNNING -> snap?.fraction ?: 0f; else -> 0f },
                color = when (p.status) { PeerStatus.DONE -> x.ok; PeerStatus.FAILED -> c.error; else -> c.primary },
            )
        }
    }
}
