package app.barq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.barq.R
import app.barq.TransferController
import app.barq.data.FileKind
import app.barq.data.MediaRepository
import app.barq.data.PickedFile
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class Tab(val label: Int, val kind: FileKind?) {
    IMAGES(R.string.tab_images, FileKind.IMAGE),
    VIDEOS(R.string.tab_videos, FileKind.VIDEO),
    AUDIO(R.string.tab_audio, FileKind.AUDIO),
    APPS(R.string.tab_apps, FileKind.APP),
    DOCS(R.string.tab_docs, null),
}

fun mediaPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/** In-app file browser matching the Pick artboard: type tabs, date-grouped grid, multi-select, sticky total bar. */
@Composable
fun PickerScreen(onBack: () -> Unit, onDone: (List<PickedFile>) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(Tab.IMAGES) }
    val selected = remember { mutableStateOf<Map<String, PickedFile>>(emptyMap()) }
    var docs by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var granted by remember { mutableStateOf(mediaPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.any { v -> v } }
    LaunchedEffect(Unit) { if (!granted) askMedia.launch(mediaPermissions().toTypedArray()) }

    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.mapNotNull { MediaRepository.fromDocument(ctx, it) }
        docs = (docs + picked).distinctBy { it.key }
        selected.value = selected.value + picked.associateBy { it.key }
    }
    BackHandler { onBack() }

    // Load the active tab's items (cached per tab for the screen's lifetime).
    val cache = remember { mutableStateOf<Map<Tab, List<PickedFile>>>(emptyMap()) }
    val items: List<PickedFile>? = when (tab) {
        Tab.DOCS -> docs
        else -> cache.value[tab]
    }
    LaunchedEffect(tab, granted) {
        if (tab == Tab.DOCS || cache.value.containsKey(tab) || !granted) return@LaunchedEffect
        val list = when (tab) {
            Tab.IMAGES -> MediaRepository.images(ctx)
            Tab.VIDEOS -> MediaRepository.videos(ctx)
            Tab.AUDIO -> MediaRepository.audio(ctx)
            Tab.APPS -> MediaRepository.apps(ctx)
            Tab.DOCS -> emptyList()
        }
        cache.value = cache.value + (tab to list)
    }

    fun toggle(f: PickedFile) {
        selected.value = if (selected.value.containsKey(f.key)) selected.value - f.key else selected.value + (f.key to f)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(BarqIcons.Back, contentDescription = stringResource(R.string.back_home)) }
            Text(stringResource(R.string.send_pick), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val visible = items.orEmpty()
            val allOn = visible.isNotEmpty() && visible.all { selected.value.containsKey(it.key) }
            if (visible.isNotEmpty() && tab != Tab.DOCS) {
                TextButton(onClick = {
                    selected.value = if (allOn) selected.value - visible.map { it.key }.toSet() else selected.value + visible.associateBy { it.key }
                }) { Text(stringResource(if (allOn) R.string.pick_none else R.string.pick_all)) }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Text(
                    stringResource(t.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) c.onPrimary else c.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).background(if (on) c.primary else c.surface)
                        .border(1.dp, if (on) c.primary else c.outline, CircleShape)
                        .clickable { tab = t; if (t == Tab.DOCS) pickDocs.launch(arrayOf("*/*")) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                !granted && tab != Tab.DOCS && tab != Tab.APPS -> Empty(stringResource(R.string.pick_need_permission)) {
                    TextButton(onClick = { askMedia.launch(mediaPermissions().toTypedArray()) }) { Text(stringResource(R.string.perm_grant)) }
                }
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> Empty(stringResource(if (tab == Tab.DOCS) R.string.pick_docs_hint else R.string.pick_empty)) {
                    if (tab == Tab.DOCS) TextButton(onClick = { pickDocs.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pick_browse)) }
                }
                tab == Tab.IMAGES || tab == Tab.VIDEOS -> MediaGrid(items, selected.value, ::toggle)
                else -> FileList(items, selected.value, ::toggle)
            }
        }

        val sel = selected.value.values
        Row(
            Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.outline).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pick_count, sel.size), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (sel.isEmpty()) stringResource(R.string.pick_hint) else formatBytes(sel.sumOf { it.size }) + " · " + stringResource(R.string.send_pick_hint),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel.isEmpty()) c.primary.copy(alpha = 0.4f) else c.primary)
                    .clickable(enabled = sel.isNotEmpty()) { onDone(sel.toList()) }.padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val session = TransferController.openSendSession
                Text(
                    if (session != null) stringResource(R.string.send_to_same, session.name) else stringResource(R.string.pick_next),
                    style = MaterialTheme.typography.labelLarge, color = c.onPrimary,
                )
                Icon(BarqIcons.Next, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MediaGrid(items: List<PickedFile>, selected: Map<String, PickedFile>, toggle: (PickedFile) -> Unit) {
    val ctx = LocalContext.current
    val groups = remember(items) { items.groupBy { dayKey(it.modifiedSec) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEach { (day, list) ->
            item(key = "h-$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(ctx, day), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.pick_items, list.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.key }) { f -> Tile(f, selected.containsKey(f.key)) { toggle(f) } }
        }
    }
}

@Composable
private fun Tile(f: PickedFile, on: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val bmp by produceState<Bitmap?>(null, f.key) { value = MediaRepository.thumbnail(ctx, f) }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(c.surface)
            .then(if (on) Modifier.border(3.dp, c.primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(
            Modifier.padding(8.dp).size(24.dp).clip(CircleShape).background(if (on) c.primary else Color.Black.copy(alpha = 0.25f))
                .border(2.dp, if (on) c.primary else Color.White, CircleShape).align(Alignment.TopStart),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(BarqIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        if (f.kind == FileKind.VIDEO && f.durationMs > 0) {
            Text(
                formatDuration(f.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FileList(items: List<PickedFile>, selected: Map<String, PickedFile>, toggle: (PickedFile) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    LazyVerticalGrid(columns = GridCells.Fixed(1), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.key }) { f ->
            val on = selected.containsKey(f.key)
            val bmp by produceState<Bitmap?>(null, f.key) { value = MediaRepository.thumbnail(ctx, f, 96) }
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(c.surface)
                    .border(if (on) 1.5.dp else 1.dp, if (on) c.primary else c.outline, MaterialTheme.shapes.medium)
                    .clickable { toggle(f) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(if (f.kind == FileKind.AUDIO) BarqIcons.Video else BarqIcons.File, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatBytes(f.size) + if (f.durationMs > 0) " · " + formatDuration(f.durationMs / 1000) else "",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(if (on) c.primary else Color.Transparent).border(2.dp, if (on) c.primary else c.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(BarqIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
private fun Empty(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        action()
    }
}

private fun dayKey(sec: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = sec * 1000; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return cal.timeInMillis
}

private fun dayLabel(ctx: android.content.Context, dayStart: Long): String {
    val today = dayKey(System.currentTimeMillis() / 1000)
    return when (dayStart) {
        today -> ctx.getString(R.string.day_today)
        today - 86_400_000L -> ctx.getString(R.string.day_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
    }
}
