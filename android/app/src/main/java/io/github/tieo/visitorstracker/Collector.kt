package io.github.tieo.visitorstracker

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tieo.visitorstracker.source.Flexappoint
import io.github.tieo.visitorstracker.source.FlexappointOffice
import io.github.tieo.visitorstracker.source.Http
import io.github.tieo.visitorstracker.source.OFFICES
import io.github.tieo.visitorstracker.source.Office
import io.github.tieo.visitorstracker.source.RateLimited
import io.github.tieo.visitorstracker.source.SmartCjm
import io.github.tieo.visitorstracker.source.SmartCjmOffice
import io.github.tieo.visitorstracker.source.appointments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One polling round over every office, run on the phone.
 *
 * Offices on the same booking system share its rate limit (smartCJM sits
 * behind a Cloudflare rule that blocks the client address after a burst).
 * Requests are spaced out, and once a system answers 429 it is left alone, in
 * this round and later ones, until its Retry-After has passed: requests sent
 * during a block only prolong it.
 */
object Collector {
    private const val TAG = "Collector"
    private const val PAUSE_MILLIS = 5_000L
    private val DEFAULT_BLOCK = Duration.ofMinutes(15)
    private val mutex = Mutex()
    private val runningFlow = MutableStateFlow(false)

    /** Whether a round is in progress, for the pull-to-refresh indicator. */
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    /**
     * One round over [offices]. A [dense] round, run around releases, reads a
     * flexappoint office only from the day before its newest known day.
     */
    suspend fun run(context: Context, offices: List<Office> = OFFICES, dense: Boolean = false) = mutex.withLock {
        runningFlow.value = true
        try {
            withContext(Dispatchers.IO) { round(context, offices, dense) }
        } finally {
            runningFlow.value = false
        }
    }

    private suspend fun round(context: Context, offices: List<Office>, dense: Boolean) {
        val store = Store.get(context)
        for (office in offices) {
            val at = Instant.now()
            val until = store.blockedUntil(office.system)
            if (until != null && until.isAfter(at)) {
                store.recordFailure(office.id, at, "skipped: ${office.system} blocked until $until")
                continue
            }
            try {
                val from = if (dense && office is FlexappointOffice) {
                    store.slots(office.id).maxOfOrNull { it.start }?.atZone(Flexappoint.BERLIN)?.toLocalDate()?.minusDays(1)
                } else {
                    null
                }
                val slots = when (office) {
                    is SmartCjmOffice -> SmartCjm.read(office, Http())
                    is FlexappointOffice -> Flexappoint.read(office, Http(), from)
                }
                val coveredFrom = from?.atStartOfDay(Flexappoint.BERLIN)?.toInstant()
                val created = store.recordSnapshot(office.id, at, slots, if (slots.isEmpty()) 0 else appointments(slots), coveredFrom)
                notifyAlerts(context, store, office, created, at)
            } catch (error: RateLimited) {
                val wait = error.retryAfterSeconds?.let { Duration.ofSeconds(it) } ?: DEFAULT_BLOCK
                store.block(office.system, at.plus(wait).plusSeconds(30))
                store.recordFailure(office.id, at, error.message ?: "rate limited")
            } catch (error: Exception) {
                Log.w(TAG, "${office.id} failed", error)
                store.recordFailure(office.id, at, "${error.javaClass.simpleName}: ${error.message}")
            }
            delay(PAUSE_MILLIS)
        }
    }

    /**
     * Raises one notification per alert that has new matches. A slot is
     * announced once per free episode: booked and freed again, it is a new row
     * and is announced again.
     */
    private fun notifyAlerts(context: Context, store: Store, office: Office, createdIds: List<Long>, now: Instant) {
        if (createdIds.isEmpty()) return
        val alerts = store.alerts(office.id)
        if (alerts.isEmpty()) return
        val created = store.slots(office.id, ids = createdIds)
        for (alert in alerts) {
            val until = LocalDate.parse(alert.until)
            val matching = created.filter {
                it.start.isAfter(now) && !it.start.atZone(Flexappoint.BERLIN).toLocalDate().isAfter(until)
            }
            val announced = store.claimHits(alert.id, matching.map { it.id }).toSet()
            val fresh = matching.filter { it.id in announced }
            if (fresh.isEmpty()) continue
            val count = appointments(fresh.map { io.github.tieo.visitorstracker.source.Slot(it.start, it.end, it.resource) })
            val earliest = format(fresh.minOf { it.start })
            val text = if (count <= 1) "Free: $earliest" else "$count free, earliest $earliest"
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) continue
            val notification = Notifications.builder(context, Notifications.FREE_SLOTS)
                .setContentTitle(office.name)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(Notifications.openApp(context, MainActivity.TAB_ALERTS))
                .build()
            NotificationManagerCompat.from(context).notify(alert.id.toInt(), notification)
        }
    }

    private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun format(moment: Instant): String = moment.atZone(Flexappoint.BERLIN).format(timeFormat)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A round on opening the app, when the data is older than a regular round would leave it. */
    fun refreshIfStale(context: Context) {
        Thread {
            val latest = Store.get(context).latestPolls()
            val oldest = OFFICES.minOfOrNull { latest[it.id] ?: Instant.EPOCH }
            if (oldest == null || Duration.between(oldest, Instant.now()) > Duration.ofMinutes(10)) runNow(context)
        }.start()
    }

    /** One round now, unless one is already queued or running. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CollectWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork("collect-now", ExistingWorkPolicy.KEEP, request)
    }
}

class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Collector.run(applicationContext)
        return Result.success()
    }
}
