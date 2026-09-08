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
         * Binary search to find the active subtitle block for a given position.
         * Assumes blocks are sorted by startMs.
         */
        fun findActiveBlock(blocks: List<SubtitleBlock>, positionMs: Long): SubtitleBlock? {
            if (blocks.isEmpty()) return null
            var lo = 0
            var hi = blocks.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val block = blocks[mid]
                when {
                    positionMs < block.startMs -> hi = mid - 1
                    positionMs > block.endMs -> lo = mid + 1
                    else -> return block
                }
            }
            // Fallback: check neighbors of where binary search landed
            if (lo in blocks.indices) {
                val b = blocks[lo]
                if (positionMs in b.startMs..b.endMs) return b
            }
            return null
        }
    }
}
