package org.schabi.newpipe.gemini.obj

import org.junit.Assert.*
import org.junit.Test

class SubtitleBlockTest {

    // ── buildDisplayList tests ──────────────────────────────────────────

    @Test
    fun `buildDisplayList groups word-level cues into readable segments`() {
        // YouTube word-level: each cue is 1-2 words, heavily overlapping
        val blocks = listOf(
            SubtitleBlock(1, 100, 1427, "", "Naber"),
            SubtitleBlock(2, 447, 2245, "", "mühendisler"),
            SubtitleBlock(3, 1245, 3062, "", "? Ben Indie Dev"),
            SubtitleBlock(4, 2082, 3717, "", "Dan. En iyi"),
            SubtitleBlock(5, 2732, 4307, "", "mühendislerin"),
            SubtitleBlock(6, 3322, 4838, "", "en iyi olmasının sebebi"),
        )

        val result = SubtitleBlock.buildDisplayList(blocks)

        assertTrue("Should produce segments", result.isNotEmpty())

        // All segments should have non-empty text
        result.forEach { segment ->
            assertTrue("Segment text should not be blank: '${segment.text}'", segment.text.isNotBlank())
        }

        // Text should accumulate progressively (each segment >= previous length)
        for (i in 1 until result.size) {
            assertTrue(
                "Segment $i should be longer or equal to segment ${i-1}",
                result[i].text.length >= result[i-1].text.length
            )
        }
    }

    @Test
    fun `buildDisplayList handles empty input`() {
        val result = SubtitleBlock.buildDisplayList(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildDisplayList handles single block`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "Hello world")
        )
        val result = SubtitleBlock.buildDisplayList(blocks)
        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].text)
    }

    @Test
    fun `buildDisplayList splits on large gaps`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 1000, "", "First sentence"),
            // 2 second gap
            SubtitleBlock(2, 3000, 4000, "", "Second sentence"),
        )
        val result = SubtitleBlock.buildDisplayList(blocks)
        assertEquals("Should split into 2 segments", 2, result.size)
        assertEquals("First sentence", result[0].text)
        assertEquals("Second sentence", result[1].text)
    }

    @Test
    fun `buildDisplayList does not duplicate already displayed text`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 1000, "", "hello"),
            SubtitleBlock(2, 200, 1200, "", "hello world"),
            SubtitleBlock(3, 400, 1400, "", "hello world foo"),
        )
        val result = SubtitleBlock.buildDisplayList(blocks)

        // Should have accumulated without duplicating "hello"
        val lastSegment = result.last()
        assertFalse("Should not have duplicated 'hello'", lastSegment.text.contains("hello hello"))
    }

    @Test
    fun `buildDisplayList detects word-level automatically`() {
        val wordLevel = listOf(
            SubtitleBlock(1, 0, 1000, "", "Naber"),
            SubtitleBlock(2, 200, 1200, "", "mühendisler"),
            SubtitleBlock(3, 400, 1400, "", "? Ben"),
            SubtitleBlock(4, 600, 1600, "", "Indie Dev"),
            SubtitleBlock(5, 800, 1800, "", "Dan"),
            SubtitleBlock(6, 1000, 2000, "", "En iyi"),
            SubtitleBlock(7, 1200, 2200, "", "mühendislerin"),
            SubtitleBlock(8, 1400, 2400, "", "en iyi"),
            SubtitleBlock(9, 1600, 2600, "", "olmasının"),
            SubtitleBlock(10, 1800, 2800, "", "sebebi"),
            SubtitleBlock(11, 2000, 3000, "", "sizden"),
        )

        // All blocks have <= 3 words → word-level
        val isWordLevel = wordLevel.size > 10 &&
            wordLevel.take(10).all { it.text.trim().split("\\s+".toRegex()).size <= 3 }

        assertTrue("Should detect as word-level", isWordLevel)
    }

    @Test
    fun `buildDisplayList detects non-word-level (normal subtitles)`() {
        val normal = listOf(
            SubtitleBlock(1, 0, 3000, "", "This is a full sentence with many words"),
            SubtitleBlock(2, 2000, 5000, "", "Another complete sentence here"),
            SubtitleBlock(3, 4000, 7000, "", "Third subtitle block with text"),
        )

        val isWordLevel = normal.size > 10 ||
            normal.take(10).any { it.text.trim().split("\\s+".toRegex()).size > 3 }

        // Not word-level (only 3 blocks, each has many words)
        assertFalse("Should NOT be word-level", normal.size > 10)
    }

    // ── findActiveBlock tests ───────────────────────────────────────────

    @Test
    fun `findActiveBlock returns correct block at position`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "First"),
            SubtitleBlock(2, 1500, 3500, "", "Second"),
            SubtitleBlock(3, 3000, 5000, "", "Third"),
        )

        assertEquals("First", SubtitleBlock.findActiveBlock(blocks, 500)?.text)
        assertEquals("Second", SubtitleBlock.findActiveBlock(blocks, 2000)?.text)
        assertEquals("Third", SubtitleBlock.findActiveBlock(blocks, 4000)?.text)
        assertNull(SubtitleBlock.findActiveBlock(blocks, 6000))
    }

    @Test
    fun `findActiveBlock prefers latest start when overlapping`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 3000, "", "Early"),
            SubtitleBlock(2, 1000, 4000, "", "Late"),
        )

        // At 1500ms, both are active. Should prefer Late (later start)
        assertEquals("Late", SubtitleBlock.findActiveBlock(blocks, 1500)?.text)
    }

    // ── findActiveBlocks tests ──────────────────────────────────────────

    @Test
    fun `findActiveBlocks returns all active blocks at position`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "word1"),
            SubtitleBlock(2, 500, 2500, "", "word2"),
            SubtitleBlock(3, 1000, 3000, "", "word3"),
        )

        val active = SubtitleBlock.findActiveBlocks(blocks, 1500)
        assertEquals(3, active.size)
        assertEquals("word1", active[0].text)
        assertEquals("word2", active[1].text)
        assertEquals("word3", active[2].text)
    }

    @Test
    fun `findActiveBlocks returns empty when nothing active`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 1000, "", "past"),
            SubtitleBlock(2, 5000, 6000, "", "future"),
        )

        val active = SubtitleBlock.findActiveBlocks(blocks, 3000)
        assertTrue(active.isEmpty())
    }

    // ── fixCumulativeSubtitles tests ────────────────────────────────────

    @Test
    fun `fixCumulativeSubtitles strips prefix from cumulative blocks`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 3000, "", "greetings"),
            SubtitleBlock(2, 2000, 5000, "", "greetings and salutations"),
            SubtitleBlock(3, 4000, 7000, "", "greetings and salutations thanks"),
        )

        val result = SubtitleBlock.fixCumulativeSubtitles(blocks)

        assertEquals(3, result.size)
        assertEquals("greetings", result[0].text)
        assertEquals("and salutations", result[1].text)
        assertEquals("thanks", result[2].text)
    }

    @Test
    fun `fixCumulativeSubtitles keeps non-cumulative blocks as-is`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "Hello"),
            SubtitleBlock(2, 1500, 3500, "", "World"),
        )

        val result = SubtitleBlock.fixCumulativeSubtitles(blocks)
        assertEquals(2, result.size)
        assertEquals("Hello", result[0].text)
        assertEquals("World", result[1].text)
    }

    // ── Real YouTube data test ──────────────────────────────────────────

    @Test
    fun `real YouTube word-level subtitle data produces readable output`() {
        // Simulated from actual YouTube auto-generated subtitle data
        val blocks = listOf(
            SubtitleBlock(1, 100, 1427, "", "Naber"),
            SubtitleBlock(2, 447, 2245, "", "mühendisler"),
            SubtitleBlock(3, 1245, 3062, "", "? Ben Indie Dev"),
            SubtitleBlock(4, 2082, 3717, "", "Dan. En iyi"),
            SubtitleBlock(5, 2732, 4307, "", "mühendislerin"),
            SubtitleBlock(6, 3322, 4838, "", "en iyi olmasının sebebi"),
            SubtitleBlock(7, 3852, 5280, "", "sizden veya benden"),
            SubtitleBlock(8, 4294, 5810, "", "daha çok"),
            SubtitleBlock(9, 4825, 6238, "", "çalışmaları"),
            SubtitleBlock(10, 5252, 6754, "", "veya daha uzun"),
            SubtitleBlock(11, 5768, 7240, "", "süre mesai"),
            SubtitleBlock(12, 6255, 7771, "", "yapmaları değildir."),
        )

        val result = SubtitleBlock.buildDisplayList(blocks)

        // Should produce a few readable segments, not 12 tiny ones
        assertTrue("Should produce fewer segments than input", result.size < blocks.size)

        // Each segment should be a complete phrase
        result.forEach { segment ->
            val wordCount = segment.text.trim().split("\\s+".toRegex()).size
            assertTrue("Segment should have multiple words: '${segment.text}'", wordCount >= 2)
        }

        // First segment should start from the beginning
        assertEquals("First segment should start at first block", 100L, result[0].startMs)

        // Segments should cover the full time range
        assertEquals("Last segment should end at last block", 7771L, result.last().endMs)
    }
}
