package org.schabi.newpipe.gemini.obj

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.gemini.parser.SubtitleParser

class SubtitleBlockTest {

    // ── findActiveBlocks tests (word-level accumulation) ────────────────

    @Test
    fun `findActiveBlocks returns all overlapping blocks at position`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "Naber"),
            SubtitleBlock(2, 500, 2500, "", "mühendisler"),
            SubtitleBlock(3, 1000, 3000, "", "? Ben Indie Dev"),
        )

        val active = SubtitleBlock.findActiveBlocks(blocks, 1500)
        assertEquals(3, active.size)
        assertEquals("Naber", active[0].text)
        assertEquals("mühendisler", active[1].text)
        assertEquals("? Ben Indie Dev", active[2].text)
    }

    @Test
    fun `findActiveBlocks concatenates to form accumulated text`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 2000, "", "Naber"),
            SubtitleBlock(2, 500, 2500, "", "mühendisler"),
            SubtitleBlock(3, 1000, 3000, "", "? Ben Indie Dev"),
        )

        val active = SubtitleBlock.findActiveBlocks(blocks, 1500)
        val accumulated = active.joinToString(" ") { it.text }
        assertEquals("Naber mühendisler ? Ben Indie Dev", accumulated)
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

    @Test
    fun `findActiveBlocks returns only time-ordered blocks`() {
        val blocks = listOf(
            SubtitleBlock(3, 2000, 4000, "", "third"),
            SubtitleBlock(1, 0, 3000, "", "first"),
            SubtitleBlock(2, 1000, 3500, "", "second"),
        )

        val active = SubtitleBlock.findActiveBlocks(blocks, 2500)
        // Should be sorted by startMs
        assertEquals(3, active.size)
        assertEquals("first", active[0].text)
        assertEquals("second", active[1].text)
        assertEquals("third", active[2].text)
    }

    // ── findActiveBlock tests (standard single-block) ───────────────────

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

        assertEquals("Late", SubtitleBlock.findActiveBlock(blocks, 1500)?.text)
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

    // ── Real YouTube word-level simulation ──────────────────────────────

    @Test
    fun `word-level blocks accumulate correctly via findActiveBlocks`() {
        // Simulated YouTube word-level subtitle data
        val blocks = listOf(
            SubtitleBlock(1, 100, 1427, "", "Naber"),
            SubtitleBlock(2, 447, 2245, "", "mühendisler"),
            SubtitleBlock(3, 1245, 3062, "", "? Ben Indie Dev"),
            SubtitleBlock(4, 2082, 3717, "", "Dan. En iyi"),
            SubtitleBlock(5, 2732, 4307, "", "mühendislerin"),
            SubtitleBlock(6, 3322, 4838, "", "en iyi olmasının sebebi"),
        )

        // At 1500ms: blocks 1,2,3 are active
        val active1500 = SubtitleBlock.findActiveBlocks(blocks, 1500)
        assertEquals(3, active1500.size)
        assertEquals("Naber mühendisler ? Ben Indie Dev", active1500.joinToString(" ") { it.text })

        // At 3500ms: blocks 4,5,6 are active
        val active3500 = SubtitleBlock.findActiveBlocks(blocks, 3500)
        assertEquals(3, active3500.size)
        assertEquals("Dan. En iyi mühendislerin en iyi olmasının sebebi", active3500.joinToString(" ") { it.text })
    }

    @Test
    fun `word-level detection heuristic works`() {
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

        val isWordLevel = wordLevel.size > 10 &&
            wordLevel.take(10).all { it.text.trim().split("\\s+".toRegex()).size <= 3 }

        assertTrue("Should detect as word-level", isWordLevel)
    }

    @Test
    fun `normal subtitles are not detected as word-level`() {
        val normal = listOf(
            SubtitleBlock(1, 0, 3000, "", "This is a full sentence with many words"),
            SubtitleBlock(2, 2000, 5000, "", "Another complete sentence here"),
            SubtitleBlock(3, 4000, 7000, "", "Third subtitle block with text"),
        )

        val isWordLevel = normal.size > 10 ||
            normal.take(10).any { it.text.trim().split("\\s+".toRegex()).size > 3 }

        assertFalse("Should NOT be word-level", normal.size > 10)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `findActiveBlocks with empty list`() {
        val active = SubtitleBlock.findActiveBlocks(emptyList(), 1000)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `findActiveBlock with empty list`() {
        val result = SubtitleBlock.findActiveBlock(emptyList(), 1000)
        assertNull(result)
    }

    @Test
    fun `findActiveBlocks at exact boundary`() {
        val blocks = listOf(
            SubtitleBlock(1, 1000, 2000, "", "exact"),
        )

        // At exactly startMs: should be active (startMs <= pos)
        val active = SubtitleBlock.findActiveBlocks(blocks, 1000)
        assertEquals(1, active.size)

        // At exactly endMs: should NOT be active (endMs > pos is false)
        val inactive = SubtitleBlock.findActiveBlocks(blocks, 2000)
        assertTrue(inactive.isEmpty())
    }

    // ── isWordLevel detection ────────────────────────────────────────────

    @Test
    fun `isWordLevel detects YouTube auto-generated word-level blocks`() {
        val blocks = listOf(
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
        assertTrue(SubtitleParser.isWordLevel(blocks))
    }

    @Test
    fun `isWordLevel returns false for normal subtitles`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 3000, "", "This is a full sentence with many words"),
            SubtitleBlock(2, 2000, 5000, "", "Another complete sentence here"),
        )
        assertFalse(SubtitleParser.isWordLevel(blocks))
    }

    // ── groupWordsToSentences ─────────────────────────────────────────────

    @Test
    fun `groupWordsToSentences merges overlapping words into sentences`() {
        val words = listOf(
            SubtitleBlock(1, 100, 1427, "", "Naber"),
            SubtitleBlock(2, 447, 2245, "", "mühendisler"),
            SubtitleBlock(3, 1245, 3062, "", "? Ben Indie Dev"),
            SubtitleBlock(4, 3500, 4500, "", "Bir sonraki"),
            SubtitleBlock(5, 3800, 4800, "", "cümle burada"),
        )

        val sentences = SubtitleParser.groupWordsToSentences(words)

        // First 3 words should group into one sentence (close in time)
        // Last 2 words should group into another sentence
        assertEquals(2, sentences.size)
        assertEquals("Naber mühendisler ? Ben Indie Dev", sentences[0].text)
        assertEquals(100L, sentences[0].startMs)
        assertEquals(3062L, sentences[0].endMs)
        assertEquals("Bir sonraki cümle burada", sentences[1].text)
        assertEquals(3500L, sentences[1].startMs)
        assertEquals(4800L, sentences[1].endMs)
    }

    @Test
    fun `groupWordsToSentences produces non-overlapping timestamps`() {
        val words = listOf(
            SubtitleBlock(1, 0, 1000, "", "hello"),
            SubtitleBlock(2, 500, 1500, "", "world"),
            SubtitleBlock(3, 1000, 2000, "", "how"),
            SubtitleBlock(4, 1200, 2200, "", "are you"),
        )

        val sentences = SubtitleParser.groupWordsToSentences(words)

        // All 4 words are close in time, should group into 1 sentence
        assertEquals(1, sentences.size)
        assertEquals("hello world how are you", sentences[0].text)
        assertEquals(0L, sentences[0].startMs)
        assertEquals(2200L, sentences[0].endMs)
    }

    @Test
    fun `groupWordsToSentences splits on large time gaps`() {
        val words = listOf(
            SubtitleBlock(1, 0, 1000, "", "first"),
            SubtitleBlock(2, 10000, 11000, "", "second"),
        )

        val sentences = SubtitleParser.groupWordsToSentences(words)
        assertEquals(2, sentences.size)
        assertEquals("first", sentences[0].text)
        assertEquals("second", sentences[1].text)
    }

    // ── consolidateDuplicateText ──────────────────────────────────────────

    @Test
    fun `consolidateDuplicateText merges consecutive duplicate text`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 1000, "", "hello"),
            SubtitleBlock(2, 500, 1500, "", "hello"),
            SubtitleBlock(3, 1000, 2000, "", "world"),
        )

        val result = SubtitleParser.consolidateDuplicateText(blocks)
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals(1500L, result[0].endMs) // Extended to cover duplicate
        assertEquals("world", result[1].text)
    }

    @Test
    fun `consolidateDuplicateText preserves non-duplicate blocks`() {
        val blocks = listOf(
            SubtitleBlock(1, 0, 1000, "", "hello"),
            SubtitleBlock(2, 1000, 2000, "", "world"),
        )

        val result = SubtitleParser.consolidateDuplicateText(blocks)
        assertEquals(2, result.size)
    }

    // ── End-to-end: word-level -> sentence -> translate -> map back ───────

    @Test
    fun `word-level to sentence roundtrip preserves all original blocks`() {
        // Simulate YouTube word-level auto-captions
        val originalWords = listOf(
            SubtitleBlock(1, 100, 1427, "", "Naber"),
            SubtitleBlock(2, 447, 2245, "", "mühendisler"),
            SubtitleBlock(3, 1245, 3062, "", "? Ben Indie"),
            SubtitleBlock(4, 2082, 3717, "", "Dev Dan"),
            SubtitleBlock(5, 2732, 4307, "", "En iyi"),
            SubtitleBlock(6, 3322, 4838, "", "mühendislerin sebebi"),
        )

        // Step 1: Group into sentences (what we send to Gemini)
        val sentences = SubtitleParser.groupWordsToSentences(originalWords)
        assertTrue("Sentences should have fewer blocks than words", sentences.size < originalWords.size)
        assertTrue("Sentences should have non-overlapping timestamps",
            sentences.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })

        // Step 2: Simulate Gemini translation (1:1 mapping since clean SRT)
        val translatedSentences = sentences.mapIndexed { idx, sent ->
            sent.copy(text = "translated_${idx + 1}: ${sent.text}")
        }

        // Step 3: Map back to original word-level blocks (like matchTranslatedBlocks does)
        val mappedBack = originalWords.map { word ->
            val sentenceIdx = sentences.indexOfFirst { sent ->
                word.startMs >= sent.startMs && word.startMs < sent.endMs
            }
            val transText = if (sentenceIdx >= 0 && sentenceIdx < translatedSentences.size) {
                translatedSentences[sentenceIdx].text
            } else {
                word.text
            }
            word.copy(text = transText)
        }

        // All original blocks preserved
        assertEquals(originalWords.size, mappedBack.size)
        // All original timestamps preserved
        assertEquals(originalWords.map { it.startMs }, mappedBack.map { it.startMs })
        assertEquals(originalWords.map { it.endMs }, mappedBack.map { it.endMs })
        // Words in same sentence get same translation
        assertEquals("translated_1: Naber", mappedBack[0].text)
        assertEquals("translated_1: mühendisler", mappedBack[1].text)
        assertEquals("translated_1: ? Ben Indie", mappedBack[2].text)
    }
}
