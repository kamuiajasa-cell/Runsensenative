package id.runsense.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Notifikasi lokal RunSense. Ikon kecil = badge-96.png (siluet putih), ikon besar = icon-192.png. */
object NotificationHelper {
    private const val CHANNEL = "runsense"
    private const val ID = 1001 // tetap: notifikasi baru menggantikan yang lama (seperti tag "runsense")

    fun show(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "RunSense", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val large = runCatching {
            ctx.assets.open("www/icons/icon-192.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_runsense)
            .setColor(ContextCompat.getColor(ctx, R.color.notif_accent))
            .setLargeIcon(large)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
    }
}
