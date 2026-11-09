package app.barq.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FileKind { IMAGE, VIDEO, AUDIO, APP, DOC }

/** One selectable file. [uri] is what the sender opens; apps use a file:// URI to their APK. */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String?,
    val kind: FileKind,
    val modifiedSec: Long = 0L,
    val durationMs: Long = 0L,
    val packageName: String? = null,
    /** Paths of the split APKs of an installed app; non-empty means [uri] is the base APK and the set is sent as one .apks. */
    val splits: List<String> = emptyList(),
    /** Folder path relative to the picked root ("Trip/Day 1"), "" for loose files. */
    val relPath: String = "",
    /** Identifies the picked folder this file came from, so the picker can select it as a unit. */
    val folderKey: String? = null,
) {
    val key: String get() = uri.toString()
}

object MediaRepository {
    private const val LIMIT = 3000

    suspend fun images(ctx: Context) = query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FileKind.IMAGE, hasDuration = false)
    suspend fun videos(ctx: Context) = query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FileKind.VIDEO, hasDuration = true)
    suspend fun audio(ctx: Context) = query(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, FileKind.AUDIO, hasDuration = true)

    private suspend fun query(ctx: Context, collection: Uri, kind: FileKind, hasDuration: Boolean): List<PickedFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<PickedFile>()
        val projection = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE); add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (hasDuration) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        runCatching {
            ctx.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iDur = if (hasDuration) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext() && out.size < LIMIT) {
                    val id = c.getLong(iId)
                    val size = c.getLong(iSize)
                    if (size <= 0) continue
                    out += PickedFile(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(iName) ?: "file_$id",
                        size = size,
                        mime = c.getString(iMime),
                        kind = kind,
                        modifiedSec = c.getLong(iDate),
                        durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L,
                    )
                }
            }
        }
        out
    }

    /** Installed apps with a launcher icon, as their base APK files. */
    suspend fun apps(ctx: Context): List<PickedFile> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName in launchable && it.packageName != ctx.packageName }
            .mapNotNull { app ->
                val apk = File(app.publicSourceDir ?: return@mapNotNull null)
                if (!apk.canRead()) return@mapNotNull null
                val label = pm.getApplicationLabel(app).toString()
                val splits = app.splitPublicSourceDirs.orEmpty().filter { File(it).canRead() }
                if (splits.isEmpty()) {
                    PickedFile(
                        uri = Uri.fromFile(apk), name = "$label.apk", size = apk.length(),
                        mime = "application/vnd.android.package-archive", kind = FileKind.APP,
                        modifiedSec = apk.lastModified() / 1000, packageName = app.packageName,
                    )
                } else {
                    // Base + splits travel as one STORE zip named .apks; the extension is not an apk one, so the
                    // mime type must stay generic or MediaStore would append ".apk" on the receiving phone.
                    PickedFile(
                        uri = Uri.fromFile(apk), name = "$label.apks", size = apk.length() + splits.sumOf { File(it).length() },
                        mime = "application/octet-stream", kind = FileKind.APP,
                        modifiedSec = apk.lastModified() / 1000, packageName = app.packageName, splits = splits,
                    )
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    /** A document chosen through the system picker ([kind] says which tab it came from, so it gets the right thumbnail). */
    fun fromDocument(ctx: Context, uri: Uri, kind: FileKind = FileKind.DOC): PickedFile? {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                }
            }
        }
        if (size < 0) size = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: return null
        return PickedFile(uri, name, size, ctx.contentResolver.getType(uri), kind)
    }

    /** Every file under a folder picked with OpenDocumentTree, with paths relative to that folder's parent (root name included). */
    suspend fun walkTree(ctx: Context, treeUri: Uri): Pair<String, List<PickedFile>> = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = runCatching {
            resolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "folder"
        val out = ArrayList<PickedFile>()
        val key = treeUri.toString()
        fun visit(docId: String, rel: String, depth: Int) {
            if (depth > 24 || out.size >= 20_000) return
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val proj = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            runCatching {
                resolver.query(children, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0); val name = c.getString(1) ?: continue
                        val mime = c.getString(2); val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val mod = if (c.isNull(4)) 0L else c.getLong(4) / 1000
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            visit(id, if (rel.isEmpty()) name else "$rel/$name", depth + 1)
                        } else if (size > 0) {
                            val kind = when {
                                mime?.startsWith("image/") == true -> FileKind.IMAGE
                                mime?.startsWith("video/") == true -> FileKind.VIDEO
                                mime?.startsWith("audio/") == true -> FileKind.AUDIO
                                else -> FileKind.DOC
                            }
                            out += PickedFile(
                                uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name = name, size = size, mime = mime,
                                kind = kind, modifiedSec = mod, relPath = rel, folderKey = key,
                            )
                        }
                    }
                }
            }
        }
        visit(rootId, rootName, 0)
        rootName to out
    }

    // ---------- thumbnails ----------

    private val thumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun thumbnail(ctx: Context, file: PickedFile, px: Int = 256): Bitmap? {
        thumbs.get(file.key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                when (file.kind) {
                    FileKind.APP -> {
                        val d = ctx.packageManager.getApplicationIcon(file.packageName!!)
                        if (d is BitmapDrawable) d.bitmap else Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
                            val c = Canvas(b); d.setBounds(0, 0, px, px); d.draw(c)
                        }
                    }
                    FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO -> if (file.folderKey == null) ctx.contentResolver.loadThumbnail(file.uri, Size(px, px), null) else null
                    FileKind.DOC -> null
                }
            }.onFailure { android.util.Log.w("Barq", "thumbnail failed for ${file.uri}: $it") }.getOrNull()
        }
        if (bmp != null) thumbs.put(file.key, bmp)
        return bmp
    }
}
