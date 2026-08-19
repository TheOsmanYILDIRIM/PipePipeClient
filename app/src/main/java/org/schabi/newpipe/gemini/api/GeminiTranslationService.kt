package org.schabi.newpipe.gemini.api

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class GeminiTranslationService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun translateChunk(chunkText: String, targetLanguage: String, maxRetries: Int = 3): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val apiKey = prefs.getString("gemini_api_key", "")?.trim().orEmpty()
        val model = prefs.getString("gemini_model", "gemini-3.5-flash-lite")?.trim()?.ifEmpty { "gemini-3.5-flash-lite" } ?: "gemini-3.5-flash-lite"

        if (apiKey.isEmpty()) {
            throw IllegalStateException("Gemini API key is not configured. Please set it in Settings -> Gemini AI Translation.")
        }

        val prompt = """
            You are a professional subtitle translator.
            Translate the following SRT subtitles into language: $targetLanguage.
            
            RULES:
            1. Maintain the EXACT same SRT structure, sequence numbers, and timestamp codes.
            2. Translate ONLY the text content of the subtitle cues naturally into $targetLanguage.
            3. Do NOT add any extra commentary, notes, markdown formatting (no ```srt or ``` tags), or metadata.
            4. Output ONLY the valid translated SRT text.
            
            SRT Subtitles to translate:
            $chunkText
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", prompt)
                        }
                        put(partObj)
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)

            val genConfig = JSONObject().apply {
                put("temperature", 0.3)
            }
            put("generationConfig", genConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errObj = JSONObject(responseString).getJSONObject("error")
                        errObj.optString("message", responseString)
                    } catch (_: Exception) {
                        responseString
                    }

                    // Retry on rate limit (429) or temporary server errors (5xx)
                    if ((response.code == 429 || response.code in 500..599) && attempt < maxRetries) {
                        val backoffMs = (1500L * (2.0.pow(attempt.toDouble()))).toLong().coerceIn(1000L, 8000L)
                        Thread.sleep(backoffMs)
                        continue
                    }
                    throw IOException("Gemini API error (HTTP ${response.code}): $errorMsg")
                }

                val root = JSONObject(responseString)
                val candidates = root.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val rawText = parts.getJSONObject(0).getString("text")

                return cleanGeminiOutput(rawText)

            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = (1500L * (2.0.pow(attempt.toDouble()))).toLong().coerceIn(1000L, 8000L)
                    try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                }
            }
        }

        throw lastException ?: IOException("Failed to translate chunk after retries")
    }

    private fun cleanGeminiOutput(raw: String): String {
        return raw
            .replace("```srt", "")
            .replace("```txt", "")
            .replace("```", "")
            .trim()
    }
}
