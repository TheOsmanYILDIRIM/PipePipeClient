package org.schabi.newpipe.gemini.parser

import org.json.JSONObject
import org.schabi.newpipe.gemini.obj.SubtitleBlock
import org.schabi.newpipe.gemini.obj.SubtitleBlock.Companion.mergeOverlapping
import java.util.Locale

object SubtitleParser {

    private val VTT_OR_SRT_TIME_LINE_REGEX = Regex(
        """((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3})"""
    )

    private val XML_TRANSCRIPT_TEXT_REGEX = Regex(
        """<text\s+[^>]*start="([\d.]+)"(?:\s+dur="([\d.]+)")?[^>]*>([\s\S]*?)</text>""",
        RegexOption.IGNORE_CASE
    )

    private val XML_TIMEDTEXT_P_REGEX = Regex(
        """<p\s+[^>]*t="(\d+)"(?:\s+d="(\d+)")?[^>]*>([\s\S]*?)</p>""",
        RegexOption.IGNORE_CASE
    )

    private val XML_TTML_P_REGEX = Regex(
        """<p\s+[^>]*begin="([^"]+)"(?:\s+end="([^"]+)")?[^>]*>([\s\S]*?)</p>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Master parser function that detects and parses any subtitle format:
     * - WebVTT
     * - SubRip (SRT)
     * - YouTube TimedText XML (format 1, 2, 3 / transcript)
     * - TTML XML
     * - YouTube JSON3 format
     */
    fun parse(rawContent: String): List<SubtitleBlock> {
        val raw = parseRaw(rawContent)
        return if (raw.isNotEmpty()) mergeOverlapping(raw) else emptyList()
    }

    private fun parseRaw(rawContent: String): List<SubtitleBlock> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Try JSON3 if it looks like JSON
        if (trimmed.startsWith("{") && trimmed.contains("\"events\"")) {
            val jsonBlocks = parseJson3(trimmed)
            if (jsonBlocks.isNotEmpty()) return jsonBlocks
        }

        // 2. Try XML if it looks like XML / HTML
        if (trimmed.startsWith("<") || trimmed.contains("<transcript") || trimmed.contains("<timedtext") || trimmed.contains("<tt")) {
            val xmlBlocks = parseXml(trimmed)
            if (xmlBlocks.isNotEmpty()) return xmlBlocks
        }

        // 3. Try standard WebVTT / SRT
        val vttBlocks = parseWebVttOrSrt(trimmed)
        if (vttBlocks.isNotEmpty()) return vttBlocks

        // 4. Fallback: try all parsers in sequence regardless of starting character
        val fallbackJson = parseJson3(trimmed)
        if (fallbackJson.isNotEmpty()) return fallbackJson

        val fallbackXml = parseXml(trimmed)
        if (fallbackXml.isNotEmpty()) return fallbackXml

        return emptyList()
    }

    private fun parseJson3(jsonStr: String): List<SubtitleBlock> {
        val blocks = mutableListOf<SubtitleBlock>()
        try {
            val root = JSONObject(jsonStr)
            val events = root.optJSONArray("events") ?: return emptyList()

            var seq = 1
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val segs = event.optJSONArray("segs") ?: continue

                val textBuilder = StringBuilder()
                for (j in 0 until segs.length()) {
                    val seg = segs.optJSONObject(j) ?: continue
                    val utf8 = seg.optString("utf8", "")
                    textBuilder.append(utf8)
                }

                val cleanText = unescapeHtml(textBuilder.toString())
                if (cleanText.isEmpty() || cleanText == "\n") continue

                val startMs = event.optLong("tStartMs", 0L)
                val durationMs = event.optLong("dDurationMs", 3000L).coerceAtLeast(500L)
                val endMs = startMs + durationMs

                val timeCode = msToTimeCode(startMs, endMs)
                blocks.add(SubtitleBlock(seq++, startMs, endMs, timeCode, cleanText))
            }
        } catch (_: Exception) {}
        return blocks
    }

    private fun parseXml(xmlStr: String): List<SubtitleBlock> {
        val blocks = mutableListOf<SubtitleBlock>()
        var seq = 1

        // Type A: <transcript><text start="1.5" dur="2.7">Hello</text>
        if (XML_TRANSCRIPT_TEXT_REGEX.containsMatchIn(xmlStr)) {
            XML_TRANSCRIPT_TEXT_REGEX.findAll(xmlStr).forEach { match ->
                val startSec = match.groupValues[1].toDoubleOrNull() ?: 0.0
                val durSec = match.groupValues[2].toDoubleOrNull() ?: 3.0
                val rawText = match.groupValues[3]

                val cleanText = unescapeHtml(rawText)
                if (cleanText.isNotEmpty()) {
                    val startMs = (startSec * 1000).toLong()
                    val endMs = startMs + (durSec * 1000).toLong().coerceAtLeast(500L)
                    blocks.add(SubtitleBlock(seq++, startMs, endMs, msToTimeCode(startMs, endMs), cleanText))
                }
            }
            if (blocks.isNotEmpty()) return blocks
        }

        // Type B: <timedtext format="3"><body><p t="1500" d="2700"><s>Hello</s></p>
        if (XML_TIMEDTEXT_P_REGEX.containsMatchIn(xmlStr)) {
            XML_TIMEDTEXT_P_REGEX.findAll(xmlStr).forEach { match ->
                val startMs = match.groupValues[1].toLongOrNull() ?: 0L
                val durMs = match.groupValues[2].toLongOrNull() ?: 3000L
                val rawText = match.groupValues[3]

                val cleanText = unescapeHtml(rawText)
                if (cleanText.isNotEmpty()) {
                    val endMs = startMs + durMs.coerceAtLeast(500L)
                    blocks.add(SubtitleBlock(seq++, startMs, endMs, msToTimeCode(startMs, endMs), cleanText))
                }
            }
            if (blocks.isNotEmpty()) return blocks
        }

        // Type C: TTML <p begin="00:00:01.500" end="00:00:04.200">Hello</p>
        if (XML_TTML_P_REGEX.containsMatchIn(xmlStr)) {
            XML_TTML_P_REGEX.findAll(xmlStr).forEach { match ->
                val beginStr = match.groupValues[1]
                val endStr = match.groupValues[2]
                val rawText = match.groupValues[3]

                val cleanText = unescapeHtml(rawText)
                if (cleanText.isNotEmpty()) {
                    val startMs = parseTimestampToMs(beginStr)
                    val endMs = if (endStr.isNotBlank()) parseTimestampToMs(endStr) else startMs + 3000L
                    blocks.add(SubtitleBlock(seq++, startMs, endMs, msToTimeCode(startMs, endMs), cleanText))
                }
            }
            if (blocks.isNotEmpty()) return blocks
        }

        return blocks
    }

    private fun parseWebVttOrSrt(content: String): List<SubtitleBlock> {
        val normalized = content
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val rawBlocks = normalized.split(Regex("\n\n+"))
        val blocks = mutableListOf<SubtitleBlock>()
        var seq = 1

        for (rawBlock in rawBlocks) {
            val lines = rawBlock.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val timeLineIdx = lines.indexOfFirst { VTT_OR_SRT_TIME_LINE_REGEX.containsMatchIn(it) }
            if (timeLineIdx == -1) continue

            val match = VTT_OR_SRT_TIME_LINE_REGEX.find(lines[timeLineIdx]) ?: continue
            val startMs = parseTimestampToMs(match.groupValues[1])
            val endMs = parseTimestampToMs(match.groupValues[2])

            val textLines = lines.drop(timeLineIdx + 1)
            val rawText = textLines
                .filter { !it.startsWith("NOTE") && !it.startsWith("STYLE") }
                .joinToString("\n")

            val cleanText = unescapeHtml(rawText)
            if (cleanText.isNotEmpty()) {
                blocks.add(SubtitleBlock(seq++, startMs, endMs, msToTimeCode(startMs, endMs), cleanText))
            }
        }

        if (blocks.isNotEmpty()) return blocks

        // Fallback: Scan line by line for single-newline separated cues
        val allLines = normalized.lines().map { it.trim() }
        var currentStartMs = 0L
        var currentEndMs = 0L
        val currentTextLines = mutableListOf<String>()
        var inCue = false

        for (line in allLines) {
            val match = VTT_OR_SRT_TIME_LINE_REGEX.find(line)
            if (match != null) {
                if (inCue && currentTextLines.isNotEmpty()) {
                    val rawText = currentTextLines
                        .filter { !it.startsWith("NOTE") && !it.startsWith("STYLE") && it.toIntOrNull() == null }
                        .joinToString("\n")
                    val cleanText = unescapeHtml(rawText)
                    if (cleanText.isNotEmpty()) {
                        blocks.add(SubtitleBlock(seq++, currentStartMs, currentEndMs, msToTimeCode(currentStartMs, currentEndMs), cleanText))
                    }
                    currentTextLines.clear()
                }
                currentStartMs = parseTimestampToMs(match.groupValues[1])
                currentEndMs = parseTimestampToMs(match.groupValues[2])
                inCue = true
            } else if (inCue) {
                if (line.isNotEmpty() && line.toIntOrNull() == null) {
                    currentTextLines.add(line)
                }
            }
        }

        if (inCue && currentTextLines.isNotEmpty()) {
            val rawText = currentTextLines
                .filter { !it.startsWith("NOTE") && !it.startsWith("STYLE") && it.toIntOrNull() == null }
                .joinToString("\n")
            val cleanText = unescapeHtml(rawText)
            if (cleanText.isNotEmpty()) {
                blocks.add(SubtitleBlock(seq++, currentStartMs, currentEndMs, msToTimeCode(currentStartMs, currentEndMs), cleanText))
            }
        }

        return blocks
    }

    private fun parseTimestampToMs(tsStr: String): Long {
        val clean = tsStr.trim().removeSuffix("s")
        if (clean.contains(":")) {
            val parts = clean.split(":")
            if (parts.size == 3) {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].replace(',', '.').split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                return h * 3600000L + m * 60000L + s * 1000L + ms
            } else if (parts.size == 2) {
                val m = parts[0].toLongOrNull() ?: 0L
                val secParts = parts[1].replace(',', '.').split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                return m * 60000L + s * 1000L + ms
            }
        } else {
            val sec = clean.toDoubleOrNull() ?: 0.0
            return (sec * 1000).toLong()
        }
        return 0L
    }

    private fun msToTimeCode(startMs: Long, endMs: Long): String {
        return "${formatMsToSrt(startMs)} --> ${formatMsToSrt(endMs)}"
    }

    private fun formatMsToSrt(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun unescapeHtml(input: String): String {
        var text = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        // Decimal entities &#123;
        text = Regex("&#(\\d+);").replace(text) { match ->
            try {
                match.groupValues[1].toInt().toChar().toString()
            } catch (_: Exception) { match.value }
        }

        // Hex entities &#x1F600;
        text = Regex("&#x([0-9a-fA-F]+);", RegexOption.IGNORE_CASE).replace(text) { match ->
            try {
                match.groupValues[1].toInt(16).toChar().toString()
            } catch (_: Exception) { match.value }
        }

        // Strip HTML/XML tags e.g. <font color="...">, </font>, <s>, <c.color>
        text = Regex("<[^>]*>").replace(text, "")
        return text.trim()
    }

    fun toSrt(blocks: List<SubtitleBlock>): String {
        return blocks.joinToString("\n\n") { it.toSrt() }
    }

    fun chunkBlocks(blocks: List<SubtitleBlock>, chunkSize: Int): List<List<SubtitleBlock>> {
        val size = if (chunkSize <= 0) 50 else chunkSize
        return blocks.chunked(size)
    }
}
