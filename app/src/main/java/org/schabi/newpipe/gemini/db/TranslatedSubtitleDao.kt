package org.schabi.newpipe.gemini.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslatedSubtitleDao {

    @Query("""
        SELECT * FROM gemini_translated_subtitles 
        WHERE videoId = :videoId 
          AND sourceLanguage = :sourceLang 
          AND targetLanguage = :targetLang 
        ORDER BY chunkIndex ASC
    """)
    fun getTranslatedChunks(
        videoId: String,
        sourceLang: String,
        targetLang: String
    ): List<TranslatedSubtitle>

    @Query("""
        SELECT * FROM gemini_translated_subtitles 
        WHERE videoId = :videoId 
          AND targetLanguage = :targetLang 
        ORDER BY chunkIndex ASC
    """)
    fun getTranslatedChunksByVideo(
        videoId: String,
        targetLang: String
    ): List<TranslatedSubtitle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChunk(chunk: TranslatedSubtitle)

    @Query("DELETE FROM gemini_translated_subtitles WHERE videoId = :videoId")
    fun deleteByVideoId(videoId: String)

    @Query("DELETE FROM gemini_translated_subtitles")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM gemini_translated_subtitles")
    fun getCacheCount(): Int
}
