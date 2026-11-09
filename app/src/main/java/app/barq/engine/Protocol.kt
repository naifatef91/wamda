package app.barq.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Wire protocol constants. See docs/spec.html, section 07.
 *
 * Frame layout (big-endian):
 *   type 1B | flags 1B | fileId 4B | offset 8B | length 4B | payload
 */
object Protocol {
    const val PORT = 48080
    const val MAGIC = 0x42525121 // "BRQ!"
    const val VERSION = 1

    const val CHUNK_SIZE = 4L * 1024 * 1024
    const val SOCKET_BUFFER = 4 * 1024 * 1024
    const val STREAM_BUFFER = 1 * 1024 * 1024
    const val DEFAULT_STREAMS = 6
    const val MAX_STREAMS = 8
    const val MAX_CONTROL_FRAME = 4 * 1024 * 1024

    object Type {
        const val HELLO = 1
        const val MANIFEST = 2
        const val NEED = 3
        const val DATA = 4
        const val DONE = 5
        const val ACK = 6
        const val ERROR = 7
    }

    object Flag {
        const val LAST_CHUNK = 1
    }

    object Role {
        const val CONTROL = 0
        const val DATA = 1
    }

    fun chunkCount(size: Long): Int =
        if (size == 0L) 1 else ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
}

/**
 * Chunk arithmetic in one place. Every offset is a Long (8 bytes on the wire) and every multiplication
 * widens before it multiplies, so files above 4 GiB never wrap a 32-bit Int.
 */
object ChunkMath {
    fun offsetOf(index: Int): Long = index.toLong() * Protocol.CHUNK_SIZE

    /** Bytes in chunk [index] of a file of [size]; 0 past the end. */
    fun lengthOf(size: Long, index: Int): Int =
        minOf(Protocol.CHUNK_SIZE, size - offsetOf(index)).coerceAtLeast(0).toInt()

    fun indexOf(offset: Long): Int = (offset / Protocol.CHUNK_SIZE).toInt()

    /** A DATA frame may only land on a chunk boundary and may not run past the end of the file. */
    fun writeAllowed(size: Long, offset: Long, len: Int): Boolean =
        offset >= 0 && len >= 0 && offset % Protocol.CHUNK_SIZE == 0L && offset <= size - len
}

data class FrameHeader(val type: Int, val flags: Int, val fileId: Int, val offset: Long, val length: Int)

object FrameIO {
    fun writeHeader(out: DataOutputStream, type: Int, flags: Int, fileId: Int, offset: Long, length: Int) {
        out.writeByte(type)
        out.writeByte(flags)
        out.writeInt(fileId)
        out.writeLong(offset)
        out.writeInt(length)
    }

    /** Returns null on a clean EOF before any header byte. */
    fun readHeader(inp: DataInputStream): FrameHeader? = try {
        FrameHeader(
            type = inp.readUnsignedByte(),
            flags = inp.readUnsignedByte(),
            fileId = inp.readInt(),
            offset = inp.readLong(),
            length = inp.readInt(),
        )
    } catch (e: EOFException) {
        null
    }

    fun writeJson(out: DataOutputStream, type: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        writeHeader(out, type, 0, 0, 0L, bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun readJson(inp: DataInputStream, expectedType: Int): String {
        val h = readHeader(inp) ?: throw EOFException("connection closed")
        if (h.type == Protocol.Type.ERROR) {
            val msg = readPayloadString(inp, h.length)
            throw ProtocolException("peer error: $msg")
        }
        if (h.type != expectedType) throw ProtocolException("expected frame $expectedType, got ${h.type}")
        return readPayloadString(inp, h.length)
    }

    private fun readPayloadString(inp: DataInputStream, length: Int): String {
        if (length < 0 || length > Protocol.MAX_CONTROL_FRAME) throw ProtocolException("control frame too large: $length")
        val buf = ByteArray(length)
        inp.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}

open class ProtocolException(message: String) : Exception(message)
