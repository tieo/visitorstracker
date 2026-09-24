package io.github.tieo.visitorstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.tieo.visitorstracker.source.OFFICES
import io.github.tieo.visitorstracker.source.Office
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private val sharedTicket = mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Earlier versions kept a server address and token here; nothing reads them any more.
        deleteSharedPreferences("settings")
        Notifications.createChannels(this)
        askForNotifications()
        Polling.apply(this)
        if (savedInstanceState == null) Collector.refreshIfStale(this)
        handle(intent)
        setContent { AppTheme { App(tab, sharedTicket) } }
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
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("preview_ticket")?.split(",")?.let { (position, count, start) ->
                TicketService.preview(TicketState("preview", "E137",
                    TicketStatus("waiting", position.toInt(), count.toInt(), "29", "12"),
                    System.currentTimeMillis(), startPosition = start.toInt()))
                tab.intValue = 2
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

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_ALERTS = "alerts"
        const val TAB_TICKET = "ticket"
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF6DA7EC), background = Color(0xFF0D0D0D), surface = Color(0xFF0D0D0D),
            surfaceContainerHighest = Color(0xFF1A1A19), surfaceContainer = Color(0xFF141413), primaryContainer = Color(0xFF104281),
            onPrimaryContainer = Color(0xFFFFFFFF), secondaryContainer = Color(0xFF184F95))
    } else {
        lightColorScheme(primary = Color(0xFF2A78D6), background = Color(0xFFF9F9F7), surface = Color(0xFFF9F9F7),
            surfaceContainerHighest = Color(0xFFF0EFEC), surfaceContainer = Color(0xFFF2F1EE), primaryContainer = Color(0xFFCDE2FB),
            onPrimaryContainer = Color(0xFF0D366B), secondaryContainer = Color(0xFFCDE2FB))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(tab: MutableIntState, sharedTicket: MutableState<String?>) {
    var openOffice by remember { mutableStateOf<Office?>(null) }

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
        ) { padding -> OfficeScreen(office, Modifier.padding(padding)) }
        return
    }

    var editingPolling by remember { mutableStateOf(false) }
    if (editingPolling) PollingDialog { editingPolling = false }
    val titles = listOf(androidx.compose.ui.res.stringResource(R.string.app_title), "Alerts", "Walk-in")
    val tabs = listOf("Offices", "Alerts", "Walk-in")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab.intValue], style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { editingPolling = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Polling settings")
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
                            label = { Text(tabs[index]) },
                        )
                    }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab.intValue) {
            0 -> OfficesScreen(modifier) { openOffice = it }
            1 -> AlertsScreen(modifier)
            else -> TicketScreen(modifier, sharedTicket)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollingDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings by Polling.settings(context).collectAsState()
    val current = settings ?: return
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Polling") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingRow("Background history") {
                    Switch(current.history, onCheckedChange = { on -> Polling.update(context) { it.copy(history = on) } })
                }
                if (current.history) {
                    Choice(Polling.HISTORY_MINUTES, current.historyMinutes) { minutes ->
                        Polling.update(context) { it.copy(historyMinutes = minutes) }
                    }
                    SettingRow("Wi-Fi only") {
                        Switch(current.wifiOnly, onCheckedChange = { on -> Polling.update(context) { it.copy(wifiOnly = on) } })
                    }
                }
                Text("Alert checks", fontWeight = FontWeight.Medium)
                Choice(Polling.ALERT_MINUTES, current.alertMinutes) { minutes ->
                    Polling.update(context) { it.copy(alertMinutes = minutes) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        control()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text("$minutes min") }
        }
    }
}

private data class AlertView(val alert: LocalAlert, val office: Office?, val freeCount: Int, val firstFree: Instant?)

@Composable
private fun AlertsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val version by Store.changes.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val alerts = produceState(emptyList<AlertView>(), version) {
        value = withContext(Dispatchers.IO) {
            val store = Store.get(context)
            store.alerts().map { alert ->
                val (count, first) = Analysis.freeUntil(store.slots(alert.office, openOnly = true),
                    LocalDate.parse(alert.until), Instant.now())
                AlertView(alert, OFFICES.firstOrNull { it.id == alert.office }, count, first)
            }
        }
    }.value

    Scaffold(
        modifier = modifier,
        // The outer scaffold already keeps clear of the system bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (alerts.isNotEmpty()) {
                FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "New alert") }
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            if (alerts.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp))
                        Text("No alerts", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { adding = true }, modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("New alert")
                        }
                    }
                }
            }
            items(alerts, key = { it.alert.id }) { view ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(view.office?.name ?: view.alert.office, fontWeight = FontWeight.SemiBold)
                            Text("until " + LocalDate.parse(view.alert.until).format(dayFormat),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                view.firstFree?.let { "${view.freeCount} free now, first " + Collector.format(it) }
                                    ?: "nothing free yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            Thread {
                                Store.get(context).deleteAlert(view.alert.id)
                                Polling.apply(context)
                            }.start()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete alert")
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        NewAlertDialog(OFFICES, onDismiss = { adding = false }) { office, until ->
            adding = false
            Thread {
                Store.get(context).addAlert(office, until)
                Polling.apply(context)
            }.start()
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
private fun TicketScreen(modifier: Modifier, sharedTicket: MutableState<String?>) {
    val state by TicketService.current.collectAsState()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var link by remember { mutableStateOf(sharedTicket.value.orEmpty()) }
    val ticket = state

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ticket == null) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally))
            Text("Follow a walk-in ticket", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            val url = Ticket.findUrl(link)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Ticket link") },
                singleLine = true,
                isError = link.isNotBlank() && url == null,
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { link = it } }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = url != null,
                onClick = { url?.let { TicketService.start(context, it) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Follow") }
            return@Column
        }

        val status = ticket.status
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ticket.number ?: "Ticket", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                when {
                    ticket.ended -> BigLine("Closed")
                    status == null -> BigLine(ticket.error ?: "Checking…")
                    status.waiting -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${status.position}", fontSize = 64.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(" of ${status.waitingCount} waiting", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(bottom = 14.dp))
                        }
                        val start = ticket.startPosition
                        val position = status.position
                        if (start != null && position != null && start > 0) {
                            Meter(1f - position.toFloat() / start)
                        }
                    }
                    else -> BigLine("Called")
                }
            }
        }
        if (status != null && status.waiting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                TicketFact("Average wait", "${status.averageWait} min", Modifier.weight(1f).fillMaxHeight())
                TicketFact("You waited", "${status.waited} min", Modifier.weight(1f).fillMaxHeight())
            }
        }
        ticket.checkedAt?.let {
            Text(
                "Checked " + java.time.Instant.ofEpochMilli(it).atZone(io.github.tieo.visitorstracker.source.Flexappoint.BERLIN)
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + (ticket.error?.takeIf { status != null }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                TicketService.stop(context)
                sharedTicket.value = null
                link = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ticket.ended) "Done" else "Stop following") }
    }
}

@Composable
private fun BigLine(text: String) {
    Text(text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
}

@Composable
private fun TicketFact(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
