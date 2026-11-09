package app.barq.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Where incoming bytes land. Files are created as pending MediaStore entries in
 * Download/Wamda, preallocated to their final size, and written at chunk offsets
 * from any thread. A small state file per unfinished file records which chunks
 * arrived, so a later session with the same (name, size, modified) resumes.
 */
class ReceiveStore(private val context: Context, private val resume: Boolean = true) {
    private val resolver = context.contentResolver
    private val stateDir = File(context.filesDir, "resume").apply { mkdirs() }

    @Serializable
    private data class ResumeState(val uri: String, val name: String, val size: Long, val modified: Long, val done: List<Int>)

    private class OpenFile(
        val entry: FileEntry,
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val channel: FileChannel,
        val done: BitSet,
        val chunkCount: Int,
        val stateFile: File,
    ) {
        var dirty = 0
        fun isComplete() = done.cardinality() >= chunkCount
    }

    private val open = ConcurrentHashMap<Int, OpenFile>()

    /** Opens or resumes every file in the manifest. Returns fileId -> missing chunk indexes (complete files omitted). */
    fun prepare(manifest: TransferManifest): Map<Int, List<Int>> {
        val missing = LinkedHashMap<Int, List<Int>>()
        for (entry in manifest.files) {
            // FR-10: a file with the same name, size and content fingerprint already on this phone is not transferred again.
            if (entry.fp != null && existsOnDevice(entry)) continue
            val stateFile = File(stateDir, keyFor(entry) + ".json")
            val resumed = if (resume) tryResume(entry, stateFile) else null
            val file = resumed ?: create(entry, stateFile)
            open[entry.id] = file
            if (!file.isComplete()) {
                missing[entry.id] = (0 until file.chunkCount).filter { !file.done.get(it) }
            }
        }
        return missing
    }

    fun write(fileId: Int, offset: Long, buf: ByteArray, len: Int) {
        val f = open[fileId] ?: throw ProtocolException("data for unknown file $fileId")
        if (offset < 0 || len < 0 || offset % Protocol.CHUNK_SIZE != 0L || offset > f.entry.size - len) throw ProtocolException("write outside file bounds")
        val bb = ByteBuffer.wrap(buf, 0, len)
        var pos = offset
        while (bb.hasRemaining()) pos += f.channel.write(bb, pos)
        val idx = (offset / Protocol.CHUNK_SIZE).toInt()
        synchronized(f) {
            f.done.set(idx)
            if (++f.dirty >= 8) persist(f)
        }
    }

    fun bytesAlready(fileId: Int): Long {
        val f = open[fileId] ?: return 0
        var total = 0L
        for (i in 0 until f.chunkCount) if (f.done.get(i)) {
            total += minOf(Protocol.CHUNK_SIZE, f.entry.size - i * Protocol.CHUNK_SIZE).coerceAtLeast(0)
        }
        return total
    }

    fun incompleteIds(): List<Int> = open.values.filter { !it.isComplete() }.map { it.entry.id }.sorted()

    /** Publishes complete files, keeps state for incomplete ones, closes everything. */
    fun finish() {
        for (f in open.values) {
            synchronized(f) {
                // No fsync here: on a multi-GB file it stalls the "done" screen for seconds.
                // The kernel writes back shortly; MediaStore sees the file once IS_PENDING clears.
                runCatching { f.channel.close() }
                runCatching { f.pfd.close() }
                if (f.isComplete()) {
                    resolver.update(f.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    f.stateFile.delete()
                } else {
                    persist(f)
                }
            }
        }
        open.clear()
    }

    private fun tryResume(entry: FileEntry, stateFile: File): OpenFile? {
        if (!stateFile.exists()) return null
        return runCatching {
            val st = wireJson.decodeFromString(ResumeState.serializer(), stateFile.readText())
            if (st.size != entry.size || st.name != entry.name) return null
            val uri = Uri.parse(st.uri)
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            val done = BitSet().apply { st.done.forEach { set(it) } }
            OpenFile(entry, uri, pfd, channel, done, Protocol.chunkCount(entry.size), stateFile)
        }.getOrElse {
            stateFile.delete()
            null
        }
    }

    private fun create(entry: FileEntry, stateFile: File): OpenFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(entry.name))
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(entry.path))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("could not create ${entry.name}")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw java.io.IOException("could not open ${entry.name}")
        if (entry.size > 0) {
            try {
                Os.posix_fallocate(pfd.fileDescriptor, 0, entry.size)
            } catch (e: ErrnoException) {
                runCatching { Os.ftruncate(pfd.fileDescriptor, entry.size) }
            }
        }
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        val f = OpenFile(entry, uri, pfd, channel, BitSet(), Protocol.chunkCount(entry.size), stateFile)
        persist(f)
        return f
    }

    private fun persist(f: OpenFile) {
        f.dirty = 0
        val done = (0 until f.chunkCount).filter { f.done.get(it) }
        val st = ResumeState(f.uri.toString(), f.entry.name, f.entry.size, f.entry.modified, done)
        runCatching { f.stateFile.writeText(wireJson.encodeToString(ResumeState.serializer(), st)) }
    }

    private fun keyFor(entry: FileEntry): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("${entry.path}|${entry.name}|${entry.size}|${entry.modified}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** "Download/Wamda" plus the sender's folder path, each segment sanitized (FR-11). */
    private fun relativePathFor(path: String): String {
        val safe = NameSanitizer.relativePath(path)
        return if (safe.isEmpty()) "Download/Wamda" else "Download/Wamda/$safe"
    }

    /** True when a file with this name, size and quick fingerprint is already in MediaStore. */
    private fun existsOnDevice(entry: FileEntry): Boolean = runCatching {
        val uri = MediaStore.Files.getContentUri("external")
        resolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?",
            arrayOf(sanitize(entry.name), entry.size.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val candidate = android.content.ContentUris.withAppendedId(uri, c.getLong(0))
                val fp = runCatching {
                    resolver.openFileDescriptor(candidate, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch -> QuickHash.of(ch, entry.size) }
                    }
                }.getOrNull()
                if (fp == entry.fp) return@runCatching true
            }
        }
        false
    }.getOrDefault(false)

    /** Same rules as PC-mode uploads: see [NameSanitizer]. */
    private fun sanitize(name: String): String = NameSanitizer.fileName(name)
}
