package app.barq.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.text.Normalizer

/** Arabic names, emoji, brackets and nested folders survive the wire, the sanitiser and the disk unchanged. */
class ManifestNamesTest {
    private val work: File = Files.createTempDirectory("wamda-names").toFile()
    private val scope = newScope()
    @After fun cleanup() { scope.shutdown(); work.deleteRecursively() }

    private data class Case(val path: String, val name: String)

    private val cases = listOf(
        Case("", "تقرير المبيعات ٢٠٢٦.pdf"),
        Case("", "صورة العائلة 🌴🎉👨‍👩‍👧.jpg"),
        Case("", "report [final] (v2) {draft} #1 & more.txt"),
        Case("مشاريع/2026/[أرشيف]/فرع 🚀", "ملاحظات (نسخة 2).txt"),
        Case("Photos/2026/Trip 🏖️/Day [1]", "IMG_0001 (copy).HEIC"),
        Case("a/b/c/d/e/f", "deep.bin"),
    )

    private fun manifest() = TransferManifest(
        sessionId = "s-1", senderName = "هاتف أحمد 📱",
        files = cases.mapIndexed { i, c -> FileEntry(i + 1, c.name, 100L + i, "application/octet-stream", 1_700_000_000_000L + i, c.path, "fp$i") },
    )

    @Test fun theManifestSurvivesTheWireFrameByFrameWithoutDistortion() {
        val original = manifest()
        val bytes = ByteArrayOutputStream().also { FrameIO.writeJson(DataOutputStream(it), Protocol.Type.MANIFEST, wireJson.encodeToString(TransferManifest.serializer(), original)) }.toByteArray()
        val back = wireJson.decodeFromString(TransferManifest.serializer(), FrameIO.readJson(DataInputStream(ByteArrayInputStream(bytes)), Protocol.Type.MANIFEST))
        assertEquals(original, back)
        // Code point for code point, including the emoji sequences and the Arabic-Indic digits.
        for ((a, b) in original.files.zip(back.files)) {
            assertEquals(a.name.codePoints().toArray().toList(), b.name.codePoints().toArray().toList())
            assertEquals(a.path, b.path)
        }
    }

    @Test fun theSanitiserLeavesLegitimateNamesAlone() {
        for (c in cases) {
            assertEquals("name changed: ${c.name}", c.name, NameSanitizer.fileName(c.name))
            assertEquals("path changed: ${c.path}", c.path, NameSanitizer.relativePath(c.path))
        }
    }

    @Test fun traversalAndBidiTricksAreStillStrippedFromNamesLikeThese() {
        assertEquals("passwd", NameSanitizer.fileName("../../etc/passwd"))
        assertTrue(!NameSanitizer.relativePath("مشاريع/../../سري").contains(".."))
        val spoof = NameSanitizer.fileName("invoice‮gpj.exe")
        assertTrue("bidi override must be removed, got $spoof", '‮' !in spoof)
    }

    @Test(timeout = 120_000) fun everyFileLandsInTheRightFolderWithTheRightNameAndBytes() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val items = cases.mapIndexed { i, c -> testItem(i + 1, c.name, 2_000 + i * 777, src, c.path) }
        val sink = DirSink(dst)
        val rig = Rig(scope, sink, streams = 2)
        try {
            val s = rig.send(items)
            assertEquals("sender failed: ${s.error}", null, s.error)
            assertTrue(s.sessionDone)
            for (it in items) {
                val landed = File(dst, (if (it.entry.path.isEmpty()) "" else it.entry.path + "/") + it.entry.name)
                assertTrue("missing on disk: ${landed.path}", landed.isFile)
                assertEquals("bytes differ: ${it.entry.name}", sha(File(src, (if (it.entry.path.isEmpty()) "" else it.entry.path + "/") + it.entry.name)), sha(landed))
            }
            // What a directory listing shows is exactly what was offered (no normalisation or escaping crept in).
            val listed = dst.walkTopDown().filter { it.isFile }.map { it.relativeTo(dst).invariantSeparatorsPath }.map { Normalizer.normalize(it, Normalizer.Form.NFC) }.toSet()
            val offered = cases.map { (if (it.path.isEmpty()) "" else it.path + "/") + it.name }.map { Normalizer.normalize(it, Normalizer.Form.NFC) }.toSet()
            assertEquals(offered, listed)
        } finally { rig.close(); sink.closeAll() }
    }
}
