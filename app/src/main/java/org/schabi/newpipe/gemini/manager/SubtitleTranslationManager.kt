package org.schabi.newpipe.gemini.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.gemini.GeminiNotificationHelper
import org.schabi.newpipe.gemini.api.GeminiTranslationService
import org.schabi.newpipe.gemini.db.GeminiDatabase
import org.schabi.newpipe.gemini.db.TranslatedSubtitle
import org.schabi.newpipe.gemini.obj.SubtitleBlock
import org.schabi.newpipe.gemini.parser.SubtitleParser
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SubtitleTranslationManager(private val context: Context) {

    private val geminiService = GeminiTranslationService(context)
    private val dao get() = GeminiDatabase.getInstance(context).translatedSubtitleDao()
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentTask: Future<*>? = null

    sealed class TranslationState {
        object Idle : TranslationState()
        object Downloading : TranslationState()
        data class Translating(val currentChunk: Int, val totalChunks: Int) : TranslationState()
        data class Ready(val totalBlocks: Int) : TranslationState()
        data class Error(val message: String) : TranslationState()
    }

    interface TranslationListener {
        fun onStateChanged(state: TranslationState)
        fun onBlocksAvailable(blocks: List<SubtitleBlock>)
    }

    private var listener: TranslationListener? = null

    fun setListener(listener: TranslationListener?) {
        this.listener = listener
    }

    fun startTranslation(
        videoId: String,
        subtitleStream: SubtitlesStream,
        targetLanguage: String
    ) {
        cancel()

        currentTask = executor.submit {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val chunkSize = prefs.getString("gemini_chunk_size", "50")
                    ?.toIntOrNull()?.coerceIn(10, 500) ?: 50
                val modelName = prefs.getString("gemini_model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
                val sourceLang = subtitleStream.languageTag?.ifBlank { "auto" } ?: "auto"

                postState(TranslationState.Downloading)
                GeminiNotificationHelper.showStarting(context)

                val rawContent = getSubtitleContent(subtitleStream)
                if (rawContent.isBlank()) {
                    val errMsg = "Failed to fetch subtitle content (empty response)"
                    postState(TranslationState.Error(errMsg))
                    GeminiNotificationHelper.showError(context, errMsg)
                    return@submit
                }

                // Save raw file for export/debugging
                saveRawSubtitleFile(videoId, sourceLang, rawContent)

                val originalBlocks = SubtitleParser.parse(rawContent)
                if (originalBlocks.isEmpty()) {
                    val preview = rawContent.take(150).replace("\n", " ")
                    val errMsg = "Unrecognized subtitle format. Preview: $preview"
                    postState(TranslationState.Error(errMsg))
                    GeminiNotificationHelper.showError(context, errMsg)
                    return@submit
                }

                val chunks = SubtitleParser.chunkBlocks(originalBlocks, chunkSize)
                val totalChunks = chunks.size

                val cachedList = dao.getTranslatedChunks(videoId, sourceLang, targetLanguage)
                val cachedMap = cachedList.associateBy { it.chunkIndex }.toMutableMap()

                val translatedChunkBlocks = Array<List<SubtitleBlock>?>(totalChunks) { idx ->
                    cachedMap[idx]?.let { cached ->
                        val parsed = SubtitleParser.parse(cached.translatedSrtContent)
                        if (parsed.isNotEmpty()) {
                            matchTranslatedBlocks(chunks[idx], parsed)
                        } else null
                    }
                }

                val cachedCount = translatedChunkBlocks.count { it != null }
                if (cachedCount > 0) {
                    val currentCombinedBlocks = buildCombinedBlocks(translatedChunkBlocks)
                    writeTempSrtFile(videoId, targetLanguage, SubtitleParser.toSrt(currentCombinedBlocks))
                    postBlocks(currentCombinedBlocks)
                    GeminiNotificationHelper.showChunk1Ready(context)
                }

                if (cachedCount == totalChunks) {
                    val currentCombinedBlocks = buildCombinedBlocks(translatedChunkBlocks)
                    writeTempSrtFile(videoId, targetLanguage, SubtitleParser.toSrt(currentCombinedBlocks))
                    postState(TranslationState.Ready(currentCombinedBlocks.size))
                    GeminiNotificationHelper.showComplete(context, currentCombinedBlocks.size)
                    return@submit
                }

                for (chunkIdx in 0 until totalChunks) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    if (translatedChunkBlocks[chunkIdx] != null) continue

                    postState(TranslationState.Translating(chunkIdx + 1, totalChunks))
                    GeminiNotificationHelper.showProgress(context, chunkIdx + 1, totalChunks)
                    val chunkToTranslate = SubtitleParser.toSrt(chunks[chunkIdx])

                    val translatedSrt = geminiService.translateChunk(chunkToTranslate, targetLanguage)
                    val parsedTranslated = SubtitleParser.parse(translatedSrt)
                    val matchedBlocks = matchTranslatedBlocks(chunks[chunkIdx], parsedTranslated, translatedSrt)

                    translatedChunkBlocks[chunkIdx] = matchedBlocks

                    dao.insertChunk(
                        TranslatedSubtitle(
                            videoId = videoId,
                            sourceLanguage = sourceLang,
                            targetLanguage = targetLanguage,
                            chunkIndex = chunkIdx,
                            totalChunks = totalChunks,
                            originalSrtContent = chunkToTranslate,
                            translatedSrtContent = SubtitleParser.toSrt(matchedBlocks),
                            modelUsed = modelName
                        )
                    )

                    val currentCombined = buildCombinedBlocks(translatedChunkBlocks)
                    writeTempSrtFile(videoId, targetLanguage, SubtitleParser.toSrt(currentCombined))
                    postBlocks(currentCombined)

                    if (chunkIdx == 0 && cachedCount == 0) {
                        GeminiNotificationHelper.showChunk1Ready(context)
                    }
                }

                val finalCombined = buildCombinedBlocks(translatedChunkBlocks)
                writeTempSrtFile(videoId, targetLanguage, SubtitleParser.toSrt(finalCombined))
                postState(TranslationState.Ready(finalCombined.size))
                GeminiNotificationHelper.showComplete(context, finalCombined.size)

            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    val errMsg = e.localizedMessage ?: "Translation failed"
                    postState(TranslationState.Error(errMsg))
                    GeminiNotificationHelper.showError(context, errMsg)
                }
            }
        }
    }

    fun cancel() {
        currentTask?.cancel(true)
        currentTask = null
        postState(TranslationState.Idle)
    }

    private fun postState(state: TranslationState) {
        mainHandler.post { listener?.onStateChanged(state) }
    }

    private fun postBlocks(blocks: List<SubtitleBlock>) {
        mainHandler.post { listener?.onBlocksAvailable(blocks) }
    }

    private fun matchTranslatedBlocks(
        originalChunk: List<SubtitleBlock>,
        translatedChunk: List<SubtitleBlock>,
        rawTranslatedText: String = ""
    ): List<SubtitleBlock> {
        if (translatedChunk.isNotEmpty()) {
            return originalChunk.mapIndexed { index, originalBlock ->
                val transText = translatedChunk.getOrNull(index)?.text?.ifBlank { originalBlock.text }
                    ?: originalBlock.text
                SubtitleBlock(
                    sequenceNumber = originalBlock.sequenceNumber,
                    startMs = originalBlock.startMs,
                    endMs = originalBlock.endMs,
                    timeCode = originalBlock.timeCode,
                    text = transText
                )
            }
        }

        val rawLines = rawTranslatedText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("-->") && it.toIntOrNull() == null }

        if (rawLines.isNotEmpty()) {
            return originalChunk.mapIndexed { index, originalBlock ->
                val transText = rawLines.getOrNull(index)?.ifBlank { originalBlock.text } ?: originalBlock.text
                SubtitleBlock(
                    sequenceNumber = originalBlock.sequenceNumber,
                    startMs = originalBlock.startMs,
                    endMs = originalBlock.endMs,
                    timeCode = originalBlock.timeCode,
                    text = transText
                )
            }
        }

        return originalChunk
    }

    private fun buildCombinedBlocks(chunks: Array<List<SubtitleBlock>?>): List<SubtitleBlock> {
        val list = mutableListOf<SubtitleBlock>()
        for (c in chunks) {
            if (c != null) list.addAll(c)
        }
        return list
    }

    private fun getSubtitleContent(stream: SubtitlesStream): String {
        val content = stream.content.orEmpty()
        if (content.isBlank()) return ""
        if (!stream.isUrl && !content.startsWith("http://") && !content.startsWith("https://")) {
            return content
        }
        val request = Request.Builder()
            .url(content)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to download subtitles: HTTP ${response.code}")
        }
        return response.body?.string().orEmpty()
    }

    private fun saveRawSubtitleFile(videoId: String, sourceLang: String, content: String) {
        try {
            val dir = File(context.cacheDir, "gemini_subtitles")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${videoId}_raw_${sourceLang}.txt")
            file.writeText(content)
        } catch (_: Exception) {}
    }

    private fun writeTempSrtFile(videoId: String, targetLanguage: String, srtContent: String) {
        try {
            val dir = File(context.cacheDir, "gemini_subtitles")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${videoId}_${targetLanguage}_translated.srt")
            file.writeText(srtContent)
        } catch (_: Exception) {}
    }
}
