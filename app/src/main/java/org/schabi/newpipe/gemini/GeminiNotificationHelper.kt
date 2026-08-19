package org.schabi.newpipe.gemini

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.schabi.newpipe.R

object GeminiNotificationHelper {

    private const val CHANNEL_ID = "gemini_subtitles_channel"
    private const val NOTIFICATION_ID = 889911

    private fun getNotificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemini AI Subtitle Translation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gemini subtitle translation progress and status notifications"
            }
            getNotificationManager(context).createNotificationChannel(channel)
        }
    }

    fun showStarting(context: Context) {
        initChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("✨ Gemini Altyazı Çevirisi")
            .setContentText("Altyazı alınıyor ve 1. chunk çevriliyor...")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        getNotificationManager(context).notify(NOTIFICATION_ID, builder.build())

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "✨ Gemini Çevirisi Başlatıldı (1. Chunk bekleniyor...)", Toast.LENGTH_SHORT).show()
        }
    }

    fun showProgress(context: Context, currentChunk: Int, totalChunks: Int) {
        initChannel(context)
        val text = "Chunk $currentChunk / $totalChunks çevriliyor..."
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("✨ Gemini Altyazı Çevirisi")
            .setContentText(text)
            .setProgress(totalChunks, currentChunk, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        getNotificationManager(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun showQuotaWaiting(context: Context, waitSeconds: Int, currentChunk: Int, totalChunks: Int) {
        initChannel(context)
        val text = "⏳ Kota sınırı (429): ${waitSeconds}s bekleniyor... ($currentChunk / $totalChunks)"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("✨ Gemini Altyazı Çevirisi")
            .setContentText(text)
            .setProgress(totalChunks, currentChunk, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        getNotificationManager(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun showChunk1Ready(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "🎬 1. Chunk hazır! Altyazı oynamaya başladı.", Toast.LENGTH_SHORT).show()
        }
    }

    fun showComplete(context: Context, totalBlocks: Int) {
        initChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("✅ Gemini Çevirisi Tamamlandı")
            .setContentText("Tüm altyazılar ($totalBlocks blok) çevrildi ve kaydedildi.")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)

        getNotificationManager(context).notify(NOTIFICATION_ID, builder.build())

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "✅ Altyazı çevirisi tamamlandı ve kaydedildi.", Toast.LENGTH_SHORT).show()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                getNotificationManager(context).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
        }, 6000)
    }

    fun showError(context: Context, errorMsg: String) {
        initChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("❌ Gemini Çeviri Hatası")
            .setContentText(errorMsg)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)

        getNotificationManager(context).notify(NOTIFICATION_ID, builder.build())

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "❌ Gemini Çeviri Hatası: $errorMsg", Toast.LENGTH_LONG).show()
        }
    }

    fun cancel(context: Context) {
        try {
            getNotificationManager(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
