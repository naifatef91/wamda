package app.barq.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StateSweepTest {
    private val dir: File = Files.createTempDirectory("wamda-state").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    private fun state(name: String, uri: String, size: Long, ageDays: Long): File = File(dir, "$name.json").also {
        it.writeText("{\"uri\":\"$uri\",\"name\":\"$name\",\"size\":$size,\"modified\":0,\"done\":[0,1]}")
        it.setLastModified(now - ageDays * day)
    }

    @Test fun onlyStatesOlderThanTheLimitAreRemoved() {
        state("fresh", "content://media/1", 1_000, ageDays = 1)
        state("edge", "content://media/2", 2_000, ageDays = 6)
        val old = state("old", "content://media/3", 4_000, ageDays = 8)
        val deleted = ArrayList<String>()

        val r = ReceiveStore.sweep(dir, now, ReceiveStore.ORPHAN_AGE_MS) { deleted += it }

        assertEquals(listOf("content://media/3"), deleted)
        assertEquals(1, r.files); assertEquals(4_000L, r.bytes)
        assertFalse(old.exists())
        assertTrue(File(dir, "fresh.json").exists() && File(dir, "edge.json").exists())
    }

    @Test fun zeroAgeMeansEverythingForTheManualButton() {
        state("a", "content://media/1", 10, ageDays = 0); state("b", "content://media/2", 20, ageDays = 3)
        val deleted = ArrayList<String>()
        val r = ReceiveStore.sweep(dir, now, 0) { deleted += it }
        assertEquals(setOf("content://media/1", "content://media/2"), deleted.toSet())
        assertEquals(2, r.files); assertEquals(30L, r.bytes)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun unreadableStateIsDroppedBecauseItCanNeverResume() {
        File(dir, "junk.json").also { it.writeText("not json"); it.setLastModified(now - 9 * day) }
        val deleted = ArrayList<String>()
        val r = ReceiveStore.sweep(dir, now, ReceiveStore.ORPHAN_AGE_MS) { deleted += it }
        assertEquals(1, r.files); assertTrue(deleted.isEmpty())
        assertFalse(File(dir, "junk.json").exists())
    }

    @Test fun aFailingPlaceholderDeleteDoesNotStopTheSweep() {
        state("a", "content://media/1", 10, ageDays = 9); state("b", "content://media/2", 20, ageDays = 9)
        val r = ReceiveStore.sweep(dir, now, ReceiveStore.ORPHAN_AGE_MS) { throw IllegalStateException("provider gone") }
        assertEquals(2, r.files)
    }

    @Test fun pendingCountsFilesAndReservedSpaceWithoutTouchingThem() {
        state("a", "content://media/1", 1_000, ageDays = 1); state("b", "content://media/2", 5_000, ageDays = 20)
        File(dir, "ignore.txt").writeText("x")
        val p = ReceiveStore.pending(dir)
        assertEquals(2, p.files); assertEquals(6_000L, p.bytes)
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test fun missingDirectoryIsEmptyNotAnError() {
        val p = ReceiveStore.pending(File(dir, "nope"))
        assertEquals(0, p.files)
        assertEquals(0, ReceiveStore.sweep(File(dir, "nope"), now, 0) { }.files)
    }
}
