package io.github.tieo.visitorstracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * When the app talks to the booking systems.
 *
 * Nothing polls unless asked to. Background history, off by default, reads
 * every office on a slow WorkManager schedule. An active event polls
 * constantly: while an alert exists, [AlertWatchService] checks the offices
 * the alerts name every few minutes; while a walk-in ticket is followed,
 * [TicketService] reads it every 20 seconds.
 */
data class PollingSettings(
    val history: Boolean = false,
    val historyMinutes: Int = 15,
    val wifiOnly: Boolean = true,
    val alertMinutes: Int = 5,
)

object Polling {
    val HISTORY_MINUTES = listOf(15, 30, 60)
    val ALERT_MINUTES = listOf(5, 10, 15)

    private val flow = MutableStateFlow<PollingSettings?>(null)

    fun settings(context: Context): StateFlow<PollingSettings?> {
        if (flow.value == null) flow.value = load(context)
        return flow.asStateFlow()
    }

    fun update(context: Context, change: (PollingSettings) -> PollingSettings) {
        val next = change(load(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("history", next.history)
            .putInt("history_minutes", next.historyMinutes)
            .putBoolean("wifi_only", next.wifiOnly)
            .putInt("alert_minutes", next.alertMinutes)
            .apply()
        flow.value = next
        apply(context)
    }

    fun load(context: Context): PollingSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = PollingSettings()
        return PollingSettings(
            prefs.getBoolean("history", default.history),
            prefs.getInt("history_minutes", default.historyMinutes),
            prefs.getBoolean("wifi_only", default.wifiOnly),
            prefs.getInt("alert_minutes", default.alertMinutes),
        )
    }

    /** Brings the schedules in line with the settings and the stored alerts. */
    fun apply(context: Context) {
        val settings = load(context)
        val work = WorkManager.getInstance(context)
        if (settings.history) {
            val network = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<CollectWorker>(settings.historyMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInitialDelay(settings.historyMinutes.toLong(), TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(HISTORY_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            work.cancelUniqueWork(HISTORY_WORK)
        }
        Thread {
            if (Store.get(context).alerts().isNotEmpty()) AlertWatchService.start(context) else AlertWatchService.stop(context)
        }.start()
    }

    private const val PREFS = "polling"
    private const val HISTORY_WORK = "collect"
}

/** Puts the alert watch back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Polling.apply(context)
        }
    }
}
