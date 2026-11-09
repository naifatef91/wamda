package app.barq.engine

/**
 * The one place that decides what a peer-supplied file name or folder path may look like.
 * Used by the phone-to-phone receiver (manifest entries) and by PC-mode uploads, so both
 * paths accept and reject exactly the same things.
 *
 * Blocked: path traversal ("..", a leading "/" or "\"), control characters, and the invisible
 * bidirectional controls that disguise extensions (U+202E makes "txt.exe" display as "exe.txt").
 * Preserved: Arabic and every other normal letter, digit, emoji and punctuation.
 */
object NameSanitizer {
    const val MAX_NAME = 255
    const val MAX_PATH = 1024
    const val MAX_DEPTH = 24
    private const val MAX_EXTENSION = 16

    private val RESERVED = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** A single file name: never a path, never empty, at most 255 characters and 255 UTF-8 bytes. */
    fun fileName(raw: String): String {
        val cleaned = clean(raw.substringAfterLast('/').substringAfterLast('\\'))
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else clip(cleaned)
    }

    /** A relative folder path of safe segments joined by "/", or "" when nothing safe remains. */
    fun relativePath(raw: String): String {
        val out = ArrayList<String>()
        var total = 0
        for (piece in raw.split('/', '\\')) {
            val seg = clean(piece)
            if (seg.isEmpty() || seg == "." || seg == "..") continue // drops traversal and a leading separator
            val safe = clip(seg)
            val cost = safe.length + if (out.isEmpty()) 0 else 1
            if (out.size >= MAX_DEPTH || total + cost > MAX_PATH) break
            out += safe
            total += cost
        }
        return out.joinToString("/")
    }

    /**
     * A peer-chosen device name made safe to show in a dialog: no control or bidi characters,
     * whitespace collapsed, at most [max] characters. Unlike file names it may keep "/" and ":".
     */
    fun displayLabel(raw: String, max: Int = 40): String {
        val kept = StringBuilder(raw.length)
        for (c in raw) if (!isControlOrBidi(c)) kept.append(c)
        var s = kept.toString().trim().replace(Regex("\\s+"), " ")
        while (s.codePointCount(0, s.length) > max) s = s.substring(0, s.offsetByCodePoints(s.length, -1))
        return s.trim()
    }

    private fun clean(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            if (isControlOrBidi(c)) continue
            b.append(if (c in RESERVED) '_' else c)
        }
        return b.toString().trim().trimEnd('.', ' ')
    }

    private fun isControlOrBidi(c: Char): Boolean {
        val n = c.code
        return n < 0x20 || n == 0x7F || n in 0x80..0x9F ||
            n in 0x202A..0x202E || n in 0x2066..0x2069 || // embeddings, overrides, isolates
            n == 0x200E || n == 0x200F || n == 0x061C || // LRM, RLM, Arabic letter mark
            n == 0x200B || n == 0xFEFF || n == 0x2028 || n == 0x2029
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** Cut to the limits without splitting a surrogate pair, keeping a short extension intact. */
    private fun clip(name: String): String {
        if (name.length <= MAX_NAME && utf8(name) <= MAX_NAME) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= MAX_EXTENSION) name.substring(dot) else ""
        var stem = name.removeSuffix(ext)
        while (stem.isNotEmpty() && (stem.length + ext.length > MAX_NAME || utf8(stem) + utf8(ext) > MAX_NAME)) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
        }
        stem = stem.trimEnd('.', ' ')
        return (if (stem.isEmpty()) "file" else stem) + ext
    }
}
