package org.schabi.newpipe.gemini.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.gemini.db.GeminiDatabase
import org.schabi.newpipe.settings.BasePreferenceFragment
import java.util.concurrent.Executors

class GeminiSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.gemini_settings, rootKey)

        val apiKeyPref = findPreference<EditTextPreference>("gemini_api_key")
        apiKeyPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val text = pref.text
            if (text.isNullOrBlank()) {
                getString(R.string.gemini_api_key_summary)
            } else {
                "•••••••••••••••• (${text.take(4)}…${text.takeLast(4)})"
            }
        }

        val clearCachePref = findPreference<Preference>("clear_gemini_cache")
        clearCachePref?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_translation_cache)
                .setMessage(R.string.clear_translation_cache_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Executors.newSingleThreadExecutor().execute {
                        GeminiDatabase.getInstance(requireContext()).translatedSubtitleDao().deleteAll()
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), R.string.translation_cache_cleared, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }
}
