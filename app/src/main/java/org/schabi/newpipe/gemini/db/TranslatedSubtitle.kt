package org.schabi.newpipe.gemini.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "gemini_translated_subtitles",
    primaryKeys = ["videoId", "sourceLanguage", "targetLanguage", "chunkIndex"],
    indices = [
        Index(value = ["videoId", "sourceLanguage", "targetLanguage"]),
        Index(value = ["videoId", "targetLanguage"])
    ]
)
data class TranslatedSubtitle(
    val videoId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val originalSrtContent: String,
    val translatedSrtContent: String,
    val modelUsed: String,
    val createdAt: Long = System.currentTimeMillis()
)
