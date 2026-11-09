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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    FOLDERS(R.string.tab_folders, null),
    DOCS(R.string.tab_docs, null),
}

/**
 * File chooser: type tabs, date-grouped grid, multi-select, sticky total bar. Photos, videos and music come
 * from the system pickers (the Android Photo Picker and the document picker), so the app needs no media
 * permission at all and only ever sees the files the user chooses.
 * [doneLabel] overrides the confirm button's text (e.g. "Share files" in PC mode).
 */
@Composable
fun PickerScreen(onBack: () -> Unit, onDone: (List<PickedFile>) -> Unit, doneLabel: String? = null) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(Tab.IMAGES) }
    val selected = remember { mutableStateOf<Map<String, PickedFile>>(emptyMap()) }
    var docs by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    // What the user picked per media tab, through the system pickers.
    var picked by remember { mutableStateOf<Map<Tab, List<PickedFile>>>(emptyMap()) }
    fun addPicked(t: Tab, uris: List<android.net.Uri>) {
        val files = uris.mapNotNull { MediaRepository.fromDocument(ctx, it, t.kind ?: FileKind.DOC) }
        picked = picked + (t to (picked[t].orEmpty() + files).distinctBy { it.key })
        selected.value = selected.value + files.associateBy { it.key }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addPicked(Tab.IMAGES, it) }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addPicked(Tab.VIDEOS, it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addPicked(Tab.AUDIO, it) }
    fun launchPicker(t: Tab) = when (t) {
        Tab.IMAGES -> pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        Tab.VIDEOS -> pickVideos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        Tab.AUDIO -> pickAudio.launch(arrayOf("audio/*"))
        else -> {}
    }
    val isMediaTab = { t: Tab -> t == Tab.IMAGES || t == Tab.VIDEOS || t == Tab.AUDIO }

    // Folders (FR-11): each picked tree is walked once and selected as a unit.
    var folders by remember { mutableStateOf<List<Pair<String, List<PickedFile>>>>(emptyList()) }
    var walking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        walking = true
        scope.launch {
            val (name, files) = MediaRepository.walkTree(ctx, uri)
            folders = folders.filter { it.first != name } + (name to files)
            selected.value = selected.value + files.associateBy { it.key }
            walking = false
        }
    }

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
        Tab.FOLDERS -> folders.flatMap { it.second }
        Tab.IMAGES, Tab.VIDEOS, Tab.AUDIO -> picked[tab].orEmpty()
        Tab.APPS -> cache.value[tab]
    }
    LaunchedEffect(tab) {
        if (tab != Tab.APPS || cache.value.containsKey(tab)) return@LaunchedEffect
        cache.value = cache.value + (tab to MediaRepository.apps(ctx))
    }

    fun toggle(f: PickedFile) {
        selected.value = if (selected.value.containsKey(f.key)) selected.value - f.key else selected.value + (f.key to f)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(stringResource(R.string.send_pick), onBack = onBack) {
            val visible = items.orEmpty()
            val allOn = visible.isNotEmpty() && visible.all { selected.value.containsKey(it.key) }
            if (visible.isNotEmpty() && tab != Tab.DOCS && tab != Tab.FOLDERS) {
                TextButton(onClick = {
                    selected.value = if (allOn) selected.value - visible.map { it.key }.toSet() else selected.value + visible.associateBy { it.key }
                }) { Text(stringResource(if (allOn) R.string.pick_none else R.string.pick_all)) }
            }
            if (isMediaTab(tab) && visible.isNotEmpty()) {
                TextButton(onClick = { launchPicker(tab) }) { Text(stringResource(R.string.pick_add)) }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.heightIn(min = 44.dp).clip(CircleShape).background(if (on) c.primary else c.surface)
                        .border(1.dp, if (on) c.primary else c.outline, CircleShape)
                        .selectable(selected = on, role = Role.Tab) {
                            tab = t
                            if (t == Tab.DOCS) pickDocs.launch(arrayOf("*/*"))
                            if (isMediaTab(t) && picked[t].isNullOrEmpty()) launchPicker(t)
                            if (t == Tab.FOLDERS && folders.isEmpty()) pickTree.launch(null)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(t.label), style = MaterialTheme.typography.labelMedium, color = if (on) c.onPrimary else c.onSurfaceVariant)
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                tab == Tab.FOLDERS -> FolderList(
                    folders, walking, selected.value,
                    onToggle = { files ->
                        val allOn = files.all { selected.value.containsKey(it.key) }
                        selected.value = if (allOn) selected.value - files.map { it.key }.toSet() else selected.value + files.associateBy { it.key }
                    },
                    onAdd = { pickTree.launch(null) },
                )
                items == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Spinner(stringResource(R.string.pick_loading))
                }
                items.isEmpty() -> Empty(stringResource(if (tab == Tab.DOCS) R.string.pick_docs_hint else if (isMediaTab(tab)) R.string.pick_media_hint else R.string.pick_empty)) {
                    if (tab == Tab.DOCS) TextButton(onClick = { pickDocs.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pick_browse)) }
                    else if (isMediaTab(tab)) TextButton(onClick = { launchPicker(tab) }) { Text(stringResource(R.string.pick_choose)) }
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
            val session = TransferController.openSendSession
            val label = doneLabel ?: if (session != null) stringResource(R.string.send_to_same, session.name) else stringResource(R.string.pick_next)
            BarqButton(
                label, { onDone(sel.toList()) }, height = 48, leading = BarqIcons.Next, fillWidth = false, enabled = sel.isNotEmpty(),
                modifier = Modifier.widthIn(max = 200.dp),
            )
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
            BarqCard(Modifier.fillMaxWidth(), borderColor = if (on) c.primary else null, borderWidth = if (on) 1.5.dp else 1.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(f) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(if (f.kind == FileKind.AUDIO) BarqIcons.Music else BarqIcons.File, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatBytes(f.size) + if (f.durationMs > 0) " · " + formatDuration(f.durationMs / 1000) else "",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                    SelectDot(on)
                }
            }
        }
    }
}

@Composable
private fun FolderList(
    folders: List<Pair<String, List<PickedFile>>>, walking: Boolean, selected: Map<String, PickedFile>,
    onToggle: (List<PickedFile>) -> Unit, onAdd: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    LazyVerticalGrid(columns = GridCells.Fixed(1), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            BarqButton(stringResource(R.string.pick_add_folder), onAdd, kind = ButtonKind.Tonal, height = 48, leading = BarqIcons.Folder)
        }
        if (walking) {
            item {
                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(stringResource(R.string.pick_walking))
                }
            }
        }
        if (folders.isEmpty() && !walking) {
            item { Text(stringResource(R.string.pick_folders_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        }
        items(folders, key = { it.first }) { (name, files) ->
            val on = files.isNotEmpty() && files.all { selected.containsKey(it.key) }
            BarqCard(Modifier.fillMaxWidth(), borderColor = if (on) c.primary else null, borderWidth = if (on) 1.5.dp else 1.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(files) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBadge(BarqIcons.Folder, c.primaryContainer, c.primary, size = 44)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.pick_folder_summary, files.size, formatBytes(files.sumOf { it.size })), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    SelectDot(on)
                }
            }
        }
    }
}

/** Round selection indicator used by the list rows. */
@Composable
private fun SelectDot(on: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (on) c.primary else Color.Transparent).border(2.dp, if (on) c.primary else c.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(BarqIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(14.dp)) }
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
