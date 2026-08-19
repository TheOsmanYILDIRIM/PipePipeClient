package org.schabi.newpipe.gemini.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TranslatedSubtitle::class],
    version = 1,
    exportSchema = false
)
abstract class GeminiDatabase : RoomDatabase() {

    abstract fun translatedSubtitleDao(): TranslatedSubtitleDao

    companion object {
        @Volatile
        private var instance: GeminiDatabase? = null

        fun getInstance(context: Context): GeminiDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GeminiDatabase::class.java,
                    "gemini_subtitles.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
