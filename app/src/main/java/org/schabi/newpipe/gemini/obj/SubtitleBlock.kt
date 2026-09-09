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
         * Returns the last block whose startMs <= positionMs and endMs > positionMs.
         */
        fun findActiveBlock(blocks: List<SubtitleBlock>, positionMs: Long): SubtitleBlock? {
            if (blocks.isEmpty()) return null

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
         * Find all blocks active at positionMs (used for word-level rolling window).
         * YouTube auto-generated subtitles use word-level cues that overlap.
         * At any given time, multiple word-cues may be active simultaneously.
         */
        fun findActiveBlocks(blocks: List<SubtitleBlock>, positionMs: Long): List<SubtitleBlock> {
            if (blocks.isEmpty()) return emptyList()

            var lo = 0
            var hi = blocks.size - 1
            var start = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (blocks[mid].startMs <= positionMs) {
                    start = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }

            if (start < 0) return emptyList()

            val active = mutableListOf<SubtitleBlock>()
            var i = start
            while (i < blocks.size && blocks[i].startMs <= positionMs) {
                val b = blocks[i]
                if (positionMs in b.startMs until b.endMs) {
                    active.add(b)
                }
                i++
            }

            return active.sortedBy { it.startMs }
        }

        /**
         * Build display list for word-level auto-generated subtitles.
         *
         * YouTube auto-captions use word-level cues where each cue is 1-2 words.
         * At any time, multiple overlapping cues may be active. We accumulate text
         * from active cues and use gap detection to split into display segments.
         *
         * Rules:
         * - Words from active cues at the same position are concatenated in order
         * - A gap of >500ms between block starts triggers a new segment
         * - If new text is a suffix of previous text, it's already displayed (skip)
         * - If previous text is a prefix of new text, only show new text
         */
        fun buildDisplayList(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleBlock>()
            var accumulated = ""
            var segmentStartMs = sorted[0].startMs
            var lastEndMs = 0L
            val GAP_THRESHOLD_MS = 500L

            for (block in sorted) {
                val currentText = block.text.trim()
                if (currentText.isEmpty()) continue

                // Detect gap: if this block starts much later than previous ended,
                // start a new display segment
                if (result.isNotEmpty() && block.startMs - lastEndMs > GAP_THRESHOLD_MS) {
                    // Save current segment
                    result.add(SubtitleBlock(
                        sequenceNumber = result.size + 1,
                        startMs = segmentStartMs,
                        endMs = lastEndMs,
                        timeCode = "",
                        text = accumulated
                    ))
                    accumulated = ""
                    segmentStartMs = block.startMs
                }

                // Add text to accumulated display
                if (accumulated.isEmpty()) {
                    accumulated = currentText
                } else {
                    // Check if already displayed (suffix match)
                    val accLower = accumulated.lowercase().trim()
                    val curLower = currentText.lowercase().trim()
                    if (accLower.endsWith(curLower)) {
                        // Already displayed, skip
                    } else {
                        accumulated = "$accumulated $currentText"
                    }
                }

                lastEndMs = maxOf(lastEndMs, block.endMs)
            }

            // Add final segment
            if (accumulated.isNotEmpty()) {
                result.add(SubtitleBlock(
                    sequenceNumber = result.size + 1,
                    startMs = segmentStartMs,
                    endMs = lastEndMs,
                    timeCode = "",
                    text = accumulated
                ))
            }

            return result
        }

        /**
         * Legacy: Fix YouTube auto-generated rolling/cumulative subtitles.
         * Kept for backward compatibility but buildDisplayList is preferred.
         */
        fun fixCumulativeSubtitles(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleBlock>()
            var prevNormalized = ""

            for (block in sorted) {
                val currentText = block.text.trim()
                if (currentText.isEmpty()) continue

                val newText = if (prevNormalized.isNotEmpty() &&
                    currentText.lowercase().startsWith(prevNormalized.lowercase())
                ) {
                    currentText.substring(prevNormalized.length).trim()
                } else {
                    currentText
                }

                if (newText.isEmpty()) continue

                val adjustedEndMs = if (result.isNotEmpty()) {
                    block.startMs.coerceAtLeast(result.last().endMs)
                } else {
                    block.endMs
                }

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
         * Merge overlapping blocks that are NOT cumulative.
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

        /**
         * Group word-level subtitle blocks into sentence families.
         *
         * YouTube auto-generated subtitles produce word-level cues where each cue
         * is 1-3 words. Words that are spoken close together (within 2s) are
         * grouped into a single display segment.
         *
         * Two grouping strategies:
         * 1. Time-based: blocks starting within 2s of each other are grouped
         * 2. Text-based: if text is cumulative (extends previous), keep the longest
         *
         * This produces natural sentence-length display segments instead of
         * rapidly replacing 1-2 word fragments.
         */
        fun groupIntoSentenceFamilies(blocks: List<SubtitleBlock>): List<SubtitleBlock> {
            if (blocks.isEmpty()) return blocks

            val sorted = blocks.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleBlock>()

            var familyText = sorted[0].text
            var familyStartMs = sorted[0].startMs
            var familyEndMs = sorted[0].endMs

            for (i in 1 until sorted.size) {
                val block = sorted[i]
                val text = block.text.trim()
                if (text.isEmpty()) continue

                val gapMs = block.startMs - familyEndMs

                // Group if: starts within 2s of family end, OR text extends family
                val isCloseInTime = gapMs < 2000L
                val familyNorm = familyText.lowercase().trim()
                val textNorm = text.lowercase().trim()
                val isTextExtension = textNorm.startsWith(familyNorm) ||
                    familyNorm.startsWith(textNorm) ||
                    textNorm.contains(familyNorm) ||
                    familyNorm.contains(textNorm)

                if (isCloseInTime || isTextExtension) {
                    // Extend current family
                    familyText = "$familyText $text".trim()
                    familyEndMs = maxOf(familyEndMs, block.endMs)
                } else {
                    // New family
                    result.add(SubtitleBlock(
                        sequenceNumber = result.size + 1,
                        startMs = familyStartMs,
                        endMs = familyEndMs,
                        timeCode = "",
                        text = familyText
                    ))
                    familyText = text
                    familyStartMs = block.startMs
                    familyEndMs = block.endMs
                }
            }

            // Add last family
            result.add(SubtitleBlock(
                sequenceNumber = result.size + 1,
                startMs = familyStartMs,
                endMs = familyEndMs,
                timeCode = "",
                text = familyText
            ))

            return result
        }
    }
}
