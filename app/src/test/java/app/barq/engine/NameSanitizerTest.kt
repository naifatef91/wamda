package app.barq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSanitizerTest {
    @Test fun traversalInFileNameKeepsOnlyTheLeaf() {
        assertEquals("passwd", NameSanitizer.fileName("../../etc/passwd"))
        assertEquals("passwd", NameSanitizer.fileName("..\\..\\windows\\passwd"))
        assertEquals("file", NameSanitizer.fileName("/"))
        assertEquals("file", NameSanitizer.fileName(".."))
        assertEquals("file", NameSanitizer.fileName("..."))
    }

    @Test fun traversalInRelativePathIsDropped() {
        val p = NameSanitizer.relativePath("../../etc/passwd")
        assertEquals("etc/passwd", p)
        assertFalse(p.split('/').contains(".."))
        assertFalse(p.startsWith("/"))
        assertEquals("a/b", NameSanitizer.relativePath("/a/./b/../"))
        assertEquals("", NameSanitizer.relativePath("/../.."))
        assertEquals("C_/x", NameSanitizer.relativePath("C:\\x")) // drive colon neutralised
    }

    @Test fun bidiOverrideCannotDisguiseTheExtension() {
        val out = NameSanitizer.fileName("file\u202Egpj.apk")
        assertEquals("filegpj.apk", out)
        for (c in listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F')) {
            assertFalse("U+%04X survived".format(c.code), NameSanitizer.fileName("a${c}b.txt").contains(c))
        }
    }

    @Test fun controlCharactersAreRemoved() {
        assertEquals("ab.txt", NameSanitizer.fileName("a\u0000b\u001F.txt\u007F"))
        assertEquals("a_b.txt", NameSanitizer.fileName("a:b.txt"))
        assertEquals("report", NameSanitizer.fileName("report. . "))
    }

    @Test fun arabicEmojiAndNormalPunctuationArePreserved() {
        val names = listOf("صورة العائلة (٢٠٢٦).jpg", "تقرير_نهائي-v2.pdf", "فيديو 🎬 الرحلة.mp4", "Ünïcödé ñame [1].txt", ".hidden")
        names.forEach { assertEquals(it, NameSanitizer.fileName(it)) }
        assertEquals("مجلد الصور/رحلة ٢٠٢٦", NameSanitizer.relativePath("مجلد الصور/رحلة ٢٠٢٦"))
    }

    @Test fun zeroWidthJoinersUsedInPersianAreKept() {
        assertTrue(NameSanitizer.fileName("می\u200Cخواهم.txt").contains('\u200C'))
    }

    @Test fun longArabicNameIsCutToBothLimitsAndKeepsItsExtension() {
        val raw = "ا".repeat(400) + ".pdf"
        val out = NameSanitizer.fileName(raw)
        assertTrue(out.length <= NameSanitizer.MAX_NAME)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= NameSanitizer.MAX_NAME)
        assertTrue(out.endsWith(".pdf"))
        assertTrue(out.startsWith("ا"))
    }

    @Test fun cuttingNeverSplitsASurrogatePair() {
        val out = NameSanitizer.fileName("😀".repeat(200) + ".png")
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= NameSanitizer.MAX_NAME)
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) // round-trips: no lone surrogate
    }

    @Test fun relativePathIsBoundedInLengthAndDepth() {
        val deep = (1..100).joinToString("/") { "d$it" }
        val p = NameSanitizer.relativePath(deep)
        assertTrue(p.split('/').size <= NameSanitizer.MAX_DEPTH)
        val wide = (1..50).joinToString("/") { "x".repeat(200) }
        assertTrue(NameSanitizer.relativePath(wide).length <= NameSanitizer.MAX_PATH)
    }
}
