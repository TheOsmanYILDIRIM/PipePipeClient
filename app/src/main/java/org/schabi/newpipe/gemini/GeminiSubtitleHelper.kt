package org.schabi.newpipe.gemini

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Menu
import com.google.android.exoplayer2.text.Cue
import com.google.android.exoplayer2.ui.SubtitleView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.gemini.db.GeminiDatabase
import org.schabi.newpipe.gemini.obj.SubtitleBlock
import org.schabi.newpipe.gemini.parser.SubtitleParser
import org.schabi.newpipe.gemini.ui.GeminiSubtitleDialog
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

object GeminiSubtitleHelper {

    fun interface SubtitleReadyCallback {
        fun onSubtitlesReady(blocks: List<SubtitleBlock>)
    }

    fun interface PositionSupplier {
        fun getCurrentPosition(): Long
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var activeBlocks: List<SubtitleBlock> = emptyList()

    @JvmStatic
    fun addCaptionMenuItem(
        context: Context,
        menu: Menu,
        groupId: Int,
        streamInfo: StreamInfo?,
        callback: SubtitleReadyCallback
    ) {
        if (streamInfo == null || streamInfo.subtitles.isNullOrEmpty()) return

        val menuItem = menu.add(groupId, Menu.NONE, Menu.NONE, R.string.gemini_translate_title)
        menuItem.setIcon(R.drawable.ic_translate)
        menuItem.setOnMenuItemClickListener {
            if (context is Activity) {
                val videoId = streamInfo.url.substringAfterLast("v=").substringBefore("&").ifBlank { streamInfo.id }
                GeminiSubtitleDialog(
                    activity = context,
                    videoId = videoId,
                    subtitles = streamInfo.subtitles,
                    onBlocksApplied = { blocks ->
                        callback.onSubtitlesReady(blocks)
                    }
                ).show()
            }
            true
        }
    }

    @JvmStatic
    fun checkAndAutoLoadCachedSubtitles(
        context: Context,
        videoId: String?,
        callback: SubtitleReadyCallback
    ) {
        if (videoId.isNullOrBlank()) return

        val targetLang = Locale.getDefault().language
        bgExecutor.execute {
            try {
                val dao = GeminiDatabase.getInstance(context).translatedSubtitleDao()
                val cached = dao.getTranslatedChunksByVideo(videoId, targetLang)
                if (cached.isNotEmpty()) {
                    val allBlocks = mutableListOf<SubtitleBlock>()
                    for (item in cached) {
                        val parsed = SubtitleParser.parse(item.translatedSrtContent)
                        allBlocks.addAll(parsed)
                    }
                    if (allBlocks.isNotEmpty()) {
                        mainHandler.post {
                            callback.onSubtitlesReady(allBlocks)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun startLiveTicker(
        positionSupplier: PositionSupplier,
        subtitleView: SubtitleView?,
        blocks: List<SubtitleBlock>
    ) {
        stopLiveTicker()
        activeBlocks = blocks

        tickerRunnable = object : Runnable {
            override fun run() {
                if (subtitleView != null && activeBlocks.isNotEmpty()) {
                    val pos = positionSupplier.getCurrentPosition()
                    val currentBlock = activeBlocks.firstOrNull { pos in it.startMs..it.endMs }
                    if (currentBlock != null) {
                        val cue = Cue.Builder().setText(currentBlock.text).build()
                        subtitleView.setCues(Collections.singletonList(cue))
                    } else {
                        subtitleView.setCues(Collections.emptyList())
                    }
                }
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(tickerRunnable!!)
    }

    @JvmStatic
    fun stopLiveTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
        activeBlocks = emptyList()
    }
}
