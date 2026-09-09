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
         * This prevents showing stale text from an earlier block that's still "active".
         */
        fun findActiveBlock(blocks: List<SubtitleBlock>, positionMs: Long): SubtitleBlock? {
            if (blocks.isEmpty()) return null

            // Find the last block whose startMs <= positionMs (most recent start)
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

            // Check if that block is still active (endMs > positionMs)
            // Also check subsequent blocks that might have started at the same time
            var best: SubtitleBlock? = null
            var i = result
            while (i < blocks.size && blocks[i].startMs <= positionMs) {
                val b = blocks[i]
                if (positionMs in b.startMs until b.endMs) {
                    // Among overlapping blocks, prefer the one with latest start
                    if (best == null || b.startMs >= best.startMs) {
                        best = b
                    }
                }
                i++
            }

            return best
        }

        /**
         * Merge overlapping blocks that have consecutive sequence numbers.
         * For auto-generated subtitles where consecutive cues overlap,
         * this produces clean, non-overlapping blocks.
         */
        fun mergeOverlapping(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val merged = mutableListOf<SubtitleBlock>()
            var current = sorted[0]

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                // Merge if overlapping or adjacent (within 100ms gap)
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
