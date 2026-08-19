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
import java.util.regex.Pattern
import kotlin.math.pow

class GeminiTranslationService(private val context: Context) {

    companion object {
        private val RETRY_DELAY_REGEX = Pattern.compile("retry\\s+in\\s+([0-9.]+)\\s*s", Pattern.CASE_INSENSITIVE)
        private val rateLimiterLock = Any()
        private val requestTimestamps = ArrayDeque<Long>()
        private var lastDispatchTime = 0L

        fun acquireRateLimit(rpm: Int, onWaiting: ((waitSeconds: Int) -> Unit)? = null) {
            if (rpm <= 0) return

            while (true) {
                val waitTimeMs: Long
                synchronized(rateLimiterLock) {
                    val now = System.currentTimeMillis()
                    val windowStart = now - 60_000L

                    // Remove timestamps older than 60 seconds
                    while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= windowStart) {
                        requestTimestamps.removeFirst()
                    }

                    if (requestTimestamps.size < rpm) {
                        // Small burst spacing (e.g. 200ms) between parallel dispatches
                        val timeSinceLast = now - lastDispatchTime
                        val spacing = 200L
                        if (timeSinceLast < spacing) {
                            waitTimeMs = spacing - timeSinceLast
                        } else {
                            requestTimestamps.addLast(now)
                            lastDispatchTime = now
                            return
                        }
                    } else {
                        // Reached RPM limit! Wait until oldest request in 60s window drops out
                        val oldest = requestTimestamps.first()
                        waitTimeMs = (oldest + 60_000L - now).coerceAtLeast(500L)
                    }
                }

                if (waitTimeMs > 1000L) {
                    val waitSec = ((waitTimeMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                    onWaiting?.invoke(waitSec)
                }

                try {
                    Thread.sleep(waitTimeMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun translateChunk(
        chunkText: String,
        targetLanguage: String,
        maxRetries: Int = 5,
        onWaitingQuota: ((waitSeconds: Int) -> Unit)? = null
    ): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val apiKey = prefs.getString("gemini_api_key", "")?.trim().orEmpty()
        val customModel = prefs.getString("gemini_custom_model", "")?.trim().orEmpty()
        val listModel = prefs.getString("gemini_model", "gemini-3.5-flash-lite")?.trim()?.ifEmpty { "gemini-3.5-flash-lite" } ?: "gemini-3.5-flash-lite"
        val model = if (customModel.isNotEmpty()) customModel else listModel

        val rpmStr = prefs.getString("gemini_rpm_limit", "15") ?: "15"
        val rpm = rpmStr.toIntOrNull() ?: 15

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

            // Disable safety filters to avoid blocking dialogue in movies / video subtitles
            val safetyArray = JSONArray().apply {
                val categories = listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                    "HARM_CATEGORY_CIVIC_INTEGRITY"
                )
                for (cat in categories) {
                    put(JSONObject().apply {
                        put("category", cat)
                        put("threshold", "BLOCK_NONE")
                    })
                }
            }
            put("safetySettings", safetyArray)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Translation task was interrupted")
                }

                acquireRateLimit(rpm, onWaitingQuota)

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

                    if (response.code == 429 && attempt < maxRetries) {
                        // Extract retry delay from message if available e.g. "retry in 18.5s"
                        var waitMs = 15_000L * (attempt + 1)
                        val matcher = RETRY_DELAY_REGEX.matcher(errorMsg)
                        if (matcher.find()) {
                            val sec = matcher.group(1)?.toDoubleOrNull()
                            if (sec != null && sec > 0) {
                                waitMs = ((sec + 1.0) * 1000).toLong()
                            }
                        }
                        val waitSec = (waitMs / 1000).toInt().coerceAtLeast(1)
                        onWaitingQuota?.invoke(waitSec)
                        Thread.sleep(waitMs)
                        continue
                    }

                    if (response.code in 500..599 && attempt < maxRetries) {
                        val backoffMs = (3000L * (2.0.pow(attempt.toDouble()))).toLong().coerceIn(3000L, 20000L)
                        Thread.sleep(backoffMs)
                        continue
                    }

                    throw IOException("Gemini API error (HTTP ${response.code}): $errorMsg")
                }

                val root = JSONObject(responseString)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val promptFeedback = root.optJSONObject("promptFeedback")
                    val blockReason = promptFeedback?.optString("blockReason", "")
                    if (!blockReason.isNullOrEmpty()) {
                        throw IOException("Gemini blocked prompt: $blockReason")
                    }
                    throw IOException("Empty response from Gemini API")
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                if (content == null) {
                    val finishReason = firstCandidate.optString("finishReason", "UNKNOWN")
                    throw IOException("Gemini candidate finished with reason: $finishReason")
                }

                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    throw IOException("Gemini candidate content has no parts")
                }

                val textBuilder = StringBuilder()
                for (p in 0 until parts.length()) {
                    val partObj = parts.optJSONObject(p)
                    val t = partObj?.optString("text", "") ?: ""
                    textBuilder.append(t)
                }

                val rawText = textBuilder.toString()
                if (rawText.isBlank()) {
                    throw IOException("Gemini returned blank translation")
                }

                return cleanGeminiOutput(rawText)

            } catch (e: Exception) {
                lastException = e
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                if (attempt < maxRetries) {
                    val backoffMs = (3000L * (2.0.pow(attempt.toDouble()))).toLong().coerceIn(3000L, 15000L)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
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
