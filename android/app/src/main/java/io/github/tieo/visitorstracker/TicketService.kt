package io.github.tieo.visitorstracker

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** What the app knows about the tracked ticket; null status until the first reply. */
data class TicketState(
    val url: String,
    val number: String? = null,
    val status: TicketStatus? = null,
    val checkedAt: Long? = null,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * Follows a walk-in ticket while its holder waits: polls the ticket page the
 * way the page polls itself, keeps the queue position in an ongoing
 * notification, and raises a loud one shortly before and when the ticket is
 * called. Reading only; the page's postpone and cancel calls are never made.
 */
class TicketService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var ticket: TicketState
        get() = tracked.value ?: TicketState("")
        set(value) {
            tracked.value = value
        }

    /** The followed link, kept so a restarted service picks the ticket up again. */
    private var savedTicket: String?
        get() = getSharedPreferences("ticket", MODE_PRIVATE).getString("url", null)
        set(value) = getSharedPreferences("ticket", MODE_PRIVATE).edit().putString("url", value).apply()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(clear = true)
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: savedTicket
        if (url == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        savedTicket = url
        ticket = TicketState(url)
        ServiceCompat.startForeground(
            this,
            Notifications.TICKET_ID,
            ongoing(ticket),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        job?.cancel()
        job = scope.launch { follow(url) }
        return START_REDELIVER_INTENT
    }

    private suspend fun follow(url: String) {
        // The queue moves while the phone sleeps in a pocket; the ticket is followed only for a visit.
        val lock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "visitorstracker:ticket")
        lock.acquire(6 * 60 * 60 * 1000L)
        try {
            followLocked(url)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private suspend fun followLocked(url: String) {
        var announcedSoon = false
        var announcedCall = false
        while (scope.isActive) {
            val (page, wait) = fetch(Ticket.statusUrl(url))
            if (page == null) {
                ticket = ticket.copy(error = "No connection", checkedAt = System.currentTimeMillis())
                show(ongoing(ticket))
                delay(wait)
                continue
            }
            val status = Ticket.parse(page)
            val number = ticket.number ?: Ticket.number(page)
            if (status == null) {
                ticket = ticket.copy(number = number, ended = true, error = null,
                    checkedAt = System.currentTimeMillis())
                alert(number, "Ticket is closed")
                stop(clear = true)
                return
            }
            ticket = TicketState(url, number, status, System.currentTimeMillis())
            show(ongoing(ticket))
            if (status.waiting && !announcedSoon && (status.position ?: Int.MAX_VALUE) <= SOON) {
                announcedSoon = true
                alert(number, "Position ${status.position}: almost your turn")
            }
            if (!status.waiting && !announcedCall) {
                announcedCall = true
                alert(number, "Called: check the display")
            }
            delay(wait)
        }
    }

    /** The page text, or null when unreachable; plus how long to wait before the next try. */
    private fun fetch(url: String): Pair<String?, Long> = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            if (code == 429) {
                val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 120
                return@runCatching null to (seconds + 5) * 1000
            }
            val text = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            (if (code < 400) text else null) to INTERVAL
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(null to INTERVAL)

    private fun ongoing(state: TicketState): Notification {
        val status = state.status
        val title = state.number ?: "Walk-in ticket"
        val text = when {
            state.error != null -> state.error
            status == null -> "Checking…"
            status.waiting -> "Position ${status.position} of ${status.waitingCount} · average ${status.averageWait} min"
            else -> "Called"
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TicketService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notifications.builder(this, Notifications.TICKET)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifications.openApp(this, MainActivity.TAB_TICKET))
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun alert(number: String?, text: String) {
        val notification = Notifications.builder(this, Notifications.TICKET_CALLED)
            .setContentTitle(number ?: "Walk-in ticket")
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(Notifications.openApp(this, MainActivity.TAB_TICKET))
            .build()
        show(notification, id = Notifications.TICKET_ID + 1 + text.hashCode())
    }

    private fun show(notification: Notification, id: Int = Notifications.TICKET_ID) {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun stop(clear: Boolean) {
        if (clear) savedTicket = null
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_URL = "url"
        private const val INTERVAL = 20_000L
        private const val SOON = 2

        private val tracked = MutableStateFlow<TicketState?>(null)
        val current: StateFlow<TicketState?> = tracked.asStateFlow()

        fun start(context: Context, url: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, TicketService::class.java).putExtra(EXTRA_URL, url),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TicketService::class.java).setAction(ACTION_STOP))
            tracked.value = null
        }
    }
}
