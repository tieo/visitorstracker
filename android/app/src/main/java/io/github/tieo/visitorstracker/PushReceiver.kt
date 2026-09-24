package io.github.tieo.visitorstracker

import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The body the server pushes when an alert finds new free appointments. */
@Serializable
data class FreeSlotPush(
    val type: String,
    val office: String,
    val name: String,
    val count: Int,
    val earliest: String,
)

class PushReceiver : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val prefs = Prefs(this)
        val old = prefs.endpoint
        prefs.endpoint = endpoint.url
        if (old == null || old == endpoint.url || !prefs.configured) return
        // Alerts are addressed to the endpoint; carry them over to the new one.
        scope.launch {
            runCatching {
                val api = Api(prefs)
                for (alert in api.alerts(old)) {
                    api.addAlert(alert.office, alert.until, endpoint.url)
                    api.deleteAlert(alert.id)
                }
            }.onFailure { Log.w(TAG, "moving alerts to the new endpoint failed", it) }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val push = runCatching {
            json.decodeFromString<FreeSlotPush>(message.content.decodeToString())
        }.getOrNull() ?: return
        if (push.type != "free_slot") return
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val text = if (push.count == 1) {
            "Free: ${formatTime(push.earliest)}"
        } else {
            "${push.count} free, earliest ${formatTime(push.earliest)}"
        }
        val notification = Notifications.builder(this, Notifications.FREE_SLOTS)
            .setContentTitle(push.name)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(Notifications.openApp(this, MainActivity.TAB_ALERTS))
            .build()
        NotificationManagerCompat.from(this).notify(push.office.hashCode(), notification)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "push registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        Prefs(this).endpoint = null
    }

    companion object {
        private const val TAG = "PushReceiver"
        private val format = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

        fun formatTime(iso: String): String =
            runCatching { OffsetDateTime.parse(iso).format(format) }.getOrDefault(iso)
    }
}
