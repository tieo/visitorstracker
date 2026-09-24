package io.github.tieo.visitorstracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val FREE_SLOTS = "free_slots"
    const val TICKET = "ticket"
    const val TICKET_CALLED = "ticket_called"
    const val WATCH = "alert_watch"
    const val TICKET_ID = 1
    const val WATCH_ID = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(FREE_SLOTS, "Free appointments", NotificationManager.IMPORTANCE_HIGH),
                // The ongoing queue position: updated every few seconds, so it stays silent.
                NotificationChannel(TICKET, "Walk-in queue", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(TICKET_CALLED, "Walk-in called", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(WATCH, "Alert watch", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun openApp(context: Context, tab: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            tab.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun builder(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notify)
}
