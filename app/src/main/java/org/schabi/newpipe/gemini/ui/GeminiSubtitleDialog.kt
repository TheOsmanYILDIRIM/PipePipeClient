package org.schabi.newpipe.gemini.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.gemini.manager.SubtitleTranslationManager
import org.schabi.newpipe.gemini.obj.SubtitleBlock
import java.io.File
import java.util.Locale

class GeminiSubtitleDialog(
    private val activity: Activity,
    private val videoId: String,
    private val subtitles: List<SubtitlesStream>,
    private val onBlocksApplied: (List<SubtitleBlock>) -> Unit
) {

    private val translationManager = SubtitleTranslationManager(activity)
    private var dialog: AlertDialog? = null

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_gemini_subtitle_translation, null)

        val targetLocale = Locale.getDefault()
        val targetLangCode = targetLocale.language
        val targetLangDisplay = targetLocale.getDisplayName(targetLocale)

        val targetInfoView = view.findViewById<TextView>(R.id.target_language_info)
        targetInfoView.text = activity.getString(
            R.string.target_language_format,
            "$targetLangDisplay ($targetLangCode)"
        )

        val progressLayout = view.findViewById<LinearLayout>(R.id.translation_progress_layout)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.translation_progress_bar)
        val statusText = view.findViewById<TextView>(R.id.translation_status_text)
        val exportButton = view.findViewById<MaterialButton>(R.id.export_subtitles_button)
        val closeButton = view.findViewById<MaterialButton>(R.id.close_button)
        val recycler = view.findViewById<RecyclerView>(R.id.subtitles_recycler)

        translationManager.setListener(object : SubtitleTranslationManager.TranslationListener {
            override fun onStateChanged(state: SubtitleTranslationManager.TranslationState) {
                when (state) {
                    is SubtitleTranslationManager.TranslationState.Idle -> {
                        progressLayout.isVisible = false
                    }
                    is SubtitleTranslationManager.TranslationState.Downloading -> {
                        progressLayout.isVisible = true
                        progressBar.isIndeterminate = true
                        statusText.text = activity.getString(R.string.downloading_subtitles)
                    }
                    is SubtitleTranslationManager.TranslationState.Translating -> {
                        progressLayout.isVisible = true
                        progressBar.isIndeterminate = false
                        progressBar.max = state.totalChunks
                        progressBar.progress = state.currentChunk
                        statusText.text = activity.getString(
                            R.string.translation_in_progress,
                            state.currentChunk,
                            state.totalChunks
                        )
                    }
                    is SubtitleTranslationManager.TranslationState.Ready -> {
                        progressLayout.isVisible = true
                        progressBar.isIndeterminate = false
                        progressBar.progress = progressBar.max
                        statusText.text = activity.getString(R.string.translation_complete)
                    }
                    is SubtitleTranslationManager.TranslationState.Error -> {
                        progressLayout.isVisible = true
                        progressBar.isIndeterminate = false
                        statusText.text = activity.getString(
                            R.string.translation_error,
                            state.message
                        )
                    }
                }
            }

            override fun onBlocksAvailable(blocks: List<SubtitleBlock>) {
                onBlocksApplied(blocks)
            }
        })

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = SubtitleAdapter(subtitles) { selectedSub ->
            translationManager.startTranslation(
                videoId = videoId,
                subtitleStream = selectedSub,
                targetLanguage = targetLangCode
            )
        }

        exportButton.setOnClickListener {
            val dir = File(activity.cacheDir, "gemini_subtitles")
            val rawFiles = dir.listFiles { file -> file.name.startsWith(videoId) }
            if (rawFiles.isNullOrEmpty()) {
                Toast.makeText(activity, R.string.no_subtitles_to_export, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val latest = rawFiles.maxByOrNull { it.lastModified() } ?: return@setOnClickListener
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Subtitles - $videoId")
                putExtra(Intent.EXTRA_TEXT, latest.readText())
            }
            activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.export_subtitles)))
        }

        closeButton.setOnClickListener {
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        dialog?.setOnDismissListener {
            // Keep translation running in background if translating
        }

        dialog?.show()
    }

    private class SubtitleAdapter(
        private val list: List<SubtitlesStream>,
        private val onClick: (SubtitlesStream) -> Unit
    ) : RecyclerView.Adapter<SubtitleAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.subtitle_name)
            val icon: ImageView = view.findViewById(R.id.subtitle_icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gemini_subtitle, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val langName = item.displayLanguageName ?: item.languageTag ?: "Unknown"
            val title = if (item.isAutoGenerated) "$langName (Auto-generated)" else langName
            holder.name.text = title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }
}
