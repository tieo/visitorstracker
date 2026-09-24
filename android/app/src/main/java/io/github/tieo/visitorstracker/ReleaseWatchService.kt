package io.github.tieo.visitorstracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tieo.visitorstracker.source.Flexappoint.BERLIN
import io.github.tieo.visitorstracker.source.OFFICES
import io.github.tieo.visitorstracker.source.Office
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * When each office releases new days, as minutes after local midnight: the
 * span between the latest poll without the new day and the first poll with it,
 * learned from the office's own releases.
 */
object ReleasePlanner {
    /** How far before and after the learned span the dense reading runs. */
    val LEAD: Duration = Duration.ofMinutes(10)
    val TAIL: Duration = Duration.ofMinutes(25)
    val INTERVAL: Duration = Duration.ofMinutes(2)

    /**
     * The span watched until an office has shown releases of its own; those
     * then replace it.
     */
    val DEFAULT_SPAN = 23 * 60 + 55 to 5

    fun spans(context: Context): Map<Office, Pair<Int, Int>> {
        val store = Store.get(context)
        return OFFICES.associateWith { office ->
            val polls = store.polls(office.id).filter { it.ok }
            Analysis.releaseWindow(Analysis.releases(polls, store.slots(office.id))) ?: DEFAULT_SPAN
        }
    }

    /** The watch period of a span that contains [now], or else the next one to come. */
    fun period(span: Pair<Int, Int>, now: Instant): Pair<Instant, Instant> {
        val today = now.atZone(BERLIN).toLocalDate()
        val candidates = (-1L..1L).map { offset ->
            val midnight = today.plusDays(offset).atStartOfDay(BERLIN)
            val start = midnight.plusMinutes(span.first.toLong()).toInstant().minus(LEAD)
            var end = midnight.plusMinutes(span.second.toLong()).toInstant().plus(TAIL)
            // A span across midnight ends on the following day.
            if (end.isBefore(start)) end = end.plus(Duration.ofDays(1))
            start to end
        }
        return candidates.firstOrNull { (start, end) -> !now.isBefore(start) && now.isBefore(end) }
            ?: candidates.filter { it.first.isAfter(now) }.minBy { it.first }
    }

    /** Offices whose watch period is running now. */
    fun due(context: Context, now: Instant): List<Office> =
        spans(context).filter { (_, span) -> period(span, now).let { (start, end) -> !now.isBefore(start) && now.isBefore(end) } }.keys.toList()

    fun scheduleNext(context: Context) {
        val now = Instant.now()
        val next = spans(context).values.map { period(it, now).first }.filter { it.isAfter(now) }.minOrNull()
            ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent(context))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getForegroundService(
        context, 1, Intent(context, ReleaseWatchService::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Reads the offices that are about to release a new day every two minutes,
 * so the moment a day appears and how fast it goes are measured in minutes
 * rather than in the quarter hours of the background history.
 */
class ReleaseWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.builder(this, Notifications.WATCH)
            .setContentTitle("Watching for new appointment days")
            .setOngoing(true)
            .setContentIntent(Notifications.openApp(this, ""))
            .build()
        ServiceCompat.startForeground(this, Notifications.RELEASE_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "visitorstracker:release-watch")
        lock.acquire(90 * 60 * 1000L)
        try {
            while (true) {
                val offices = ReleasePlanner.due(this, Instant.now())
                if (offices.isEmpty()) break
                Collector.run(this, offices, dense = true)
                delay(ReleasePlanner.INTERVAL.toMillis())
            }
        } finally {
            if (lock.isHeld) lock.release()
            ReleasePlanner.scheduleNext(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun startIfDue(context: Context) {
            Thread {
                if (ReleasePlanner.due(context, Instant.now()).isNotEmpty()) {
                    ContextCompat.startForegroundService(context, Intent(context, ReleaseWatchService::class.java))
                }
            }.start()
        }
    }
}
