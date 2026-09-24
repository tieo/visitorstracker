package io.github.tieo.visitorstracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private val sharedTicket = mutableStateOf<String?>(null)
    private val settingsVersion = mutableIntStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        askForNotifications()
        registerPush()
        handle(intent)
        setContent {
            AppTheme {
                // A setup link rebuilds the app state with the new settings.
                androidx.compose.runtime.key(settingsVersion.intValue) {
                    App(Prefs(this), tab, sharedTicket, ::registerPush)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.getStringExtra(EXTRA_TAB)) {
            TAB_ALERTS -> tab.intValue = 1
            TAB_TICKET -> tab.intValue = 2
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "visitorstracker" && data.host == "setup") {
            val server = data.getQueryParameter("server")
            val token = data.getQueryParameter("token")
            if (server != null && server.startsWith("https://") && !token.isNullOrBlank()) {
                val prefs = Prefs(this)
                prefs.server = server
                prefs.token = token
                settingsVersion.intValue++
            }
        }
        if (intent.action == Intent.ACTION_SEND) {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(Ticket::findUrl) ?: return
            TicketService.start(this, url)
            tab.intValue = 2
            sharedTicket.value = url
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun registerPush() {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { found ->
            if (found) UnifiedPush.register(this)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_ALERTS = "alerts"
        const val TAB_TICKET = "ticket"
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val blue = Color(0xFF2A78D6)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF6DA7EC), background = Color(0xFF0D0D0D), surface = Color(0xFF1A1A19))
    } else {
        lightColorScheme(primary = blue, background = Color(0xFFF9F9F7), surface = Color(0xFFFCFCFB))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    prefs: Prefs,
    tab: androidx.compose.runtime.MutableIntState,
    sharedTicket: androidx.compose.runtime.MutableState<String?>,
    registerPush: () -> Unit,
) {
    var configured by remember { mutableStateOf(prefs.configured) }
    var editingSettings by remember { mutableStateOf(!prefs.configured) }
    var openOffice by remember { mutableStateOf<Office?>(null) }
    var offices by remember { mutableStateOf<List<Office>>(emptyList()) }

    if (editingSettings) {
        SettingsDialog(prefs, canDismiss = configured) {
            configured = prefs.configured
            editingSettings = false
        }
    }

    openOffice?.let { office ->
        BackHandler { openOffice = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(office.name) },
                    navigationIcon = {
                        IconButton(onClick = { openOffice = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding -> OfficePage(Api(prefs), office, Modifier.padding(padding)) }
        return
    }

    val titles = listOf("Offices", "Alerts", "Walk-in")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab.intValue]) },
                actions = {
                    IconButton(onClick = { editingSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(Icons.Filled.Place, Icons.Filled.Notifications, Icons.Filled.ConfirmationNumber)
                    .forEachIndexed { index, icon ->
                        NavigationBarItem(
                            selected = tab.intValue == index,
                            onClick = { tab.intValue = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(titles[index]) },
                        )
                    }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab.intValue) {
            0 -> OfficesScreen(prefs, configured, modifier, onLoaded = { offices = it }) { openOffice = it }
            1 -> AlertsScreen(prefs, configured, offices, registerPush, modifier)
            else -> TicketScreen(modifier, sharedTicket)
        }
    }
}

@Composable
private fun SettingsDialog(prefs: Prefs, canDismiss: Boolean, onDone: () -> Unit) {
    var server by remember { mutableStateOf(prefs.server.ifEmpty { "https://" }) }
    var token by remember { mutableStateOf(prefs.token) }
    val valid = server.startsWith("https://") && server.length > 8 && token.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDone() },
        title = { Text("Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, singleLine = true,
                    label = { Text("Address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                prefs.server = server
                prefs.token = token
                onDone()
            }) { Text("Save") }
        },
        dismissButton = if (canDismiss) {
            { TextButton(onClick = onDone) { Text("Cancel") } }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficesScreen(
    prefs: Prefs,
    configured: Boolean,
    modifier: Modifier,
    onLoaded: (List<Office>) -> Unit,
    onOpen: (Office) -> Unit,
) {
    var offices by remember { mutableStateOf<List<Office>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val load: () -> Unit = {
        scope.launch {
            loading = true
            runCatching { Api(prefs).offices() }
                .onSuccess { offices = it; error = null; onLoaded(it) }
                .onFailure { error = it.message ?: "Could not load" }
            loading = false
        }
    }
    LaunchedEffect(configured, prefs.server, prefs.token) { if (configured) load() }

    PullToRefreshBox(isRefreshing = loading, onRefresh = load, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(offices, key = { it.id }) { office ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(office) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(office.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                office.nextFree?.let { "next " + PushReceiver.formatTime(it) } ?: "nothing free",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (office.lastPollOk == false) {
                                Text(
                                    "⚠ last poll failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text("${office.freeNow}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficePage(api: Api, office: Office, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // The page is self-contained, so only this first request needs the token.
                webViewClient = WebViewClient()
                loadUrl(api.pageUrl(office.id), api.authHeaders())
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertsScreen(
    prefs: Prefs,
    configured: Boolean,
    offices: List<Office>,
    registerPush: () -> Unit,
    modifier: Modifier,
) {
    var alerts by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var knownOffices by remember { mutableStateOf(offices) }
    val scope = rememberCoroutineScope()
    val endpoint by prefs.endpointChanges.collectAsState()
    val reload: () -> Unit = {
        scope.launch {
            runCatching {
                val api = Api(prefs)
                if (knownOffices.isEmpty()) knownOffices = api.offices()
                api.alerts(endpoint)
            }.onSuccess { alerts = it; error = null }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(configured, endpoint) { if (configured && endpoint != null) reload() }
    val names = knownOffices.associate { it.id to it.name }

    Scaffold(
        modifier = modifier,
        // The outer scaffold already keeps clear of the system bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (endpoint != null && configured) {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New alert")
                }
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            if (endpoint == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No push service", fontWeight = FontWeight.SemiBold)
                            OutlinedButton(onClick = registerPush) { Text("Connect") }
                        }
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(alerts, key = { it.id }) { alert ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names[alert.office] ?: alert.office, fontWeight = FontWeight.SemiBold)
                            Text(
                                "until " + LocalDate.parse(alert.until).format(dayFormat),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                alert.firstFree?.let {
                                    "${alert.freeCount} free now, first " + PushReceiver.formatTime(it)
                                } ?: "nothing free yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { Api(prefs).deleteAlert(alert.id) }
                                    .onFailure { error = it.message }
                                reload()
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete alert") }
                    }
                }
            }
        }
    }

    val target = endpoint
    if (adding && target != null) {
        NewAlertDialog(knownOffices, onDismiss = { adding = false }) { office, until ->
            adding = false
            scope.launch {
                runCatching { Api(prefs).addAlert(office, until, target) }
                    .onFailure { error = it.message }
                reload()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAlertDialog(offices: List<Office>, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var office by remember { mutableStateOf(offices.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var until by remember { mutableStateOf(LocalDate.now().plusDays(7)) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = until.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        until = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    picking = false
                }) { Text("OK") }
            },
        ) { DatePicker(state) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = office?.name.orEmpty(), onValueChange = {}, readOnly = true,
                        label = { Text("Office") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        offices.forEach {
                            DropdownMenuItem(text = { Text(it.name) }, onClick = {
                                office = it
                                expanded = false
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Until " + until.format(dayFormat))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = office != null, onClick = {
                onCreate(office!!.id, until.toString())
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TicketScreen(modifier: Modifier, sharedTicket: androidx.compose.runtime.MutableState<String?>) {
    val state by TicketService.current.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var link by remember { mutableStateOf(sharedTicket.value.orEmpty()) }

    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val ticket = state
        if (ticket != null) {
            val status = ticket.status
            Text(ticket.number ?: "Ticket", fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            when {
                ticket.ended -> Text("Closed", fontSize = 22.sp)
                status == null -> Text(ticket.error ?: "Checking…", fontSize = 22.sp)
                status.waiting -> {
                    Text("Position ${status.position} of ${status.waitingCount}", fontSize = 26.sp)
                    Text(
                        "average wait ${status.averageWait} min · waiting ${status.waited} min",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text("Called", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
            }
            ticket.error?.takeIf { status != null }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                TicketService.stop(context)
                sharedTicket.value = null
                link = ""
            }) { Text(if (ticket.ended) "Done" else "Stop") }
        } else {
            val url = Ticket.findUrl(link)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Ticket link") },
                isError = link.isNotBlank() && url == null,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = url != null, onClick = { url?.let { TicketService.start(context, it) } }) {
                Text("Follow")
            }
        }
    }
}
