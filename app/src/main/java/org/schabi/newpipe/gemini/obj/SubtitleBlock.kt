package org.schabi.newpipe.gemini.obj

import java.io.Serializable

data class SubtitleBlock(
    val sequenceNumber: Int,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val timeCode: String,
    val text: String
) : Serializable {
    fun toSrt(): String = "$sequenceNumber\n$timeCode\n$text"

    companion object {
        /**
         * Find the active block at positionMs.
         * When multiple blocks overlap (common with auto-generated subtitles),
         * returns the one with the LATEST start time — the most recently spoken line.
         */
        fun findActiveBlock(blocks: List<SubtitleBlock>, positionMs: Long): SubtitleBlock? {
            if (blocks.isEmpty()) return null

            // Find the last block whose startMs <= positionMs
            var lo = 0
            var hi = blocks.size - 1
            var result = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (blocks[mid].startMs <= positionMs) {
                    result = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }

            if (result < 0) return null

            // Among blocks active at this position, prefer latest start
            var best: SubtitleBlock? = null
            var i = result
            while (i < blocks.size && blocks[i].startMs <= positionMs) {
                val b = blocks[i]
                if (positionMs in b.startMs until b.endMs) {
                    if (best == null || b.startMs >= best.startMs) {
                        best = b
                    }
                }
                i++
            }

            return best
        }

        /**
         * Fix YouTube auto-generated rolling/cumulative subtitles.
         *
         * YouTube auto-captions work like this:
         *   1: 0-3s   "greetings"
         *   2: 2-5s   "greetings and salutations"   ← cumulative
         *   3: 4-7s   "greetings and salutations thanks"  ← cumulative
         *
         * Each segment contains ALL previously spoken words + new words.
         * This method strips the prefix shared with the previous block,
         * keeping only the NEW text for each segment.
         *
         * Also adjusts endMs so segments don't overlap:
         *   Before: 1: 0-3s "greetings", 2: 2-5s "greetings and salutations"
         *   After:  1: 0-2s "greetings", 2: 2-5s "and salutations"
         */
        fun fixCumulativeSubtitles(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleBlock>()
            var prevNormalized = ""

            for (block in sorted) {
                val currentText = block.text.trim()
                if (currentText.isEmpty()) continue

                // Check if current text starts with previous text (cumulative pattern)
                val newText = if (prevNormalized.isNotEmpty() &&
                    currentText.lowercase().startsWith(prevNormalized.lowercase())
                ) {
                    // Strip the prefix — keep only the new portion
                    currentText.substring(prevNormalized.length).trim()
                } else {
                    // Not cumulative (or first block), use as-is
                    currentText
                }

                if (newText.isEmpty()) continue

                // Adjust endMs to not overlap with next block's start
                val adjustedEndMs = if (result.isNotEmpty()) {
                    block.startMs.coerceAtLeast(result.last().endMs)
                } else {
                    block.endMs
                }

                // Fix previous block's end to not overlap with this block's start
                if (result.isNotEmpty()) {
                    val prev = result.last()
                    if (prev.endMs > block.startMs) {
                        result[result.lastIndex] = prev.copy(endMs = block.startMs)
                    }
                }

                result.add(block.copy(
                    text = newText,
                    endMs = adjustedEndMs
                ))
                prevNormalized = currentText
            }

            return result
        }

        /**
         * Merge overlapping blocks that are NOT cumulative (independent subtitles
         * that happen to overlap in time, e.g. from different speakers).
         */
        fun mergeOverlapping(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val merged = mutableListOf<SubtitleBlock>()
            var current = sorted[0]

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.startMs <= current.endMs + 100) {
                    current = SubtitleBlock(
                        sequenceNumber = current.sequenceNumber,
                        startMs = current.startMs,
                        endMs = maxOf(current.endMs, next.endMs),
                        timeCode = current.timeCode,
                        text = "${current.text} ${next.text}".trim()
                    )
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
            return merged
        }
    }
}
