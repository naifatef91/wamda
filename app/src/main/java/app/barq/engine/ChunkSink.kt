package app.barq.engine

/**
 * Where the receiver puts incoming chunks. On the phone this is [ReceiveStore] (MediaStore files,
 * preallocated, resumable); keeping it behind an interface lets the whole protocol run in plain JVM
 * tests against a temp directory.
 */
interface ChunkSink {
    /** Opens or resumes every file in the manifest; returns fileId -> missing chunk indexes (complete files omitted). */
    fun prepare(manifest: TransferManifest): Map<Int, List<Int>>

    /** Writes one chunk at its absolute [offset]; safe to call from several threads at once. */
    fun write(fileId: Int, offset: Long, buf: ByteArray, len: Int)

    fun bytesAlready(fileId: Int): Long

    fun incompleteIds(): List<Int>

    /** Publishes complete files, keeps the state of incomplete ones, closes everything. */
    fun finish()
}
