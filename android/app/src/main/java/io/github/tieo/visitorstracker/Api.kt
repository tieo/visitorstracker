package io.github.tieo.visitorstracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Office(
    val id: String,
    val name: String,
    val authority: String,
    @SerialName("free_now") val freeNow: Int,
    @SerialName("next_free") val nextFree: String? = null,
    @SerialName("last_poll") val lastPoll: String? = null,
    @SerialName("last_poll_ok") val lastPollOk: Boolean? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class Alert(
    val id: Int,
    val office: String,
    val until: String,
    @SerialName("free_count") val freeCount: Int = 0,
    @SerialName("first_free") val firstFree: String? = null,
)

@Serializable
private data class NewAlert(val office: String, val until: String, val endpoint: String)

class ApiError(message: String) : Exception(message)

/** Server address, API token and push endpoint, kept in app-private storage. */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var server: String
        get() = store.getString("server", "").orEmpty()
        set(value) = store.edit().putString("server", value.trim().trimEnd('/')).apply()
    var token: String
        get() = store.getString("token", "").orEmpty()
        set(value) = store.edit().putString("token", value.trim()).apply()
    var endpoint: String?
        get() = store.getString("endpoint", null)
        set(value) {
            store.edit().putString("endpoint", value).apply()
            endpointFlow.value = value
        }
    var ticketUrl: String?
        get() = store.getString("ticket", null)
        set(value) = store.edit().putString("ticket", value).apply()

    val configured: Boolean get() = server.startsWith("https://") && token.isNotEmpty()

    /** The push endpoint as it changes, for screens open while the distributor answers. */
    val endpointChanges: StateFlow<String?>
        get() = endpointFlow.also { if (it.value == null) it.value = endpoint }

    companion object {
        private val endpointFlow = MutableStateFlow<String?>(null)
    }
}

class Api(private val server: String, private val token: String) {
    constructor(prefs: Prefs) : this(prefs.server, prefs.token)

    private val json = Json { ignoreUnknownKeys = true }

    fun authHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer $token")

    fun pageUrl(office: String) = "$server/app/o/$office"

    suspend fun offices(): List<Office> = json.decodeFromString(request("GET", "/api/offices"))

    suspend fun alerts(endpoint: String?): List<Alert> {
        val query = endpoint?.let { "?endpoint=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return json.decodeFromString(request("GET", "/api/alerts$query"))
    }

    suspend fun addAlert(office: String, until: String, endpoint: String): Alert =
        json.decodeFromString(
            request("POST", "/api/alerts", json.encodeToString(NewAlert(office, until, endpoint))),
        )

    suspend fun deleteAlert(id: Int) {
        request("DELETE", "/api/alerts/$id")
    }

    private suspend fun request(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val connection = URL(server + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                authHeaders().forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                val stream = if (code < 400) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code >= 400) throw ApiError(errorText(code, text))
                text
            } finally {
                connection.disconnect()
            }
        }

    private fun errorText(code: Int, text: String): String {
        val message = runCatching {
            json.decodeFromString<Map<String, String>>(text)["error"]
        }.getOrNull()
        return when {
            code == 401 -> "Token rejected"
            message != null -> message
            else -> "Server answered $code"
        }
    }
}
