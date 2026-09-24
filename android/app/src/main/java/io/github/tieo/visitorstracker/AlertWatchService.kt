package io.github.tieo.visitorstracker

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tieo.visitorstracker.source.OFFICES_BY_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Watches the offices that alerts name, for as long as any alert exists.
 *
 * Runs as a foreground service so the watch is visible and the process
 * survives. The CPU sleeps between checks: each check is woken by an exact
 * alarm allowed while idle, holds a wake lock only while it reads, and sets
 * the alarm for the next one.
 */
class AlertWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheck: Instant? = null
    private var watching = 0
    private var checking: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelAlarm(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, Notifications.WATCH_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        // Settings changes and alert edits start the service again; only the alarm tick, or a
        // watch without a recent check, reads the offices. The others just keep the alarm set.
        val recent = lastCheck?.let { java.time.Duration.between(it, Instant.now()).toMinutes() < 1 } ?: false
        if (checking?.isActive == true) return START_STICKY
        checking = if (intent?.action == ACTION_TICK || !recent) {
            scope.launch { check() }
        } else {
            scheduleNext(this)
            null
        }
        return START_STICKY
    }

    private suspend fun check() {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "visitorstracker:alert-check")
        lock.acquire(10 * 60 * 1000L)
        try {
            val store = Store.get(this)
            val offices = store.alerts().mapNotNull { OFFICES_BY_ID[it.office] }.distinct()
            if (offices.isEmpty()) {
                stop(this)
                return
            }
            watching = store.alerts().size
            Collector.run(this, offices)
            lastCheck = Instant.now()
            show(notification())
            scheduleNext(this)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun notification(): Notification {
        val text = lastCheck?.let { "checked " + Collector.format(it).substringAfterLast(' ') } ?: "checking…"
        return Notifications.builder(this, Notifications.WATCH)
            .setContentTitle(if (watching == 1) "Watching 1 alert" else "Watching $watching alerts")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifications.openApp(this, MainActivity.TAB_ALERTS))
            .build()
    }

    private fun show(notification: Notification) {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(Notifications.WATCH_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val ACTION_TICK = "tick"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlertWatchService::class.java))
        }

        fun stop(context: Context) {
            cancelAlarm(context)
            context.stopService(Intent(context, AlertWatchService::class.java))
        }

        private fun tick(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, 0, Intent(context, AlertWatchService::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun scheduleNext(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + Polling.load(context).alertMinutes * 60_000L
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tick(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tick(context))
            }
        }

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(tick(context))
        }
    }
}
