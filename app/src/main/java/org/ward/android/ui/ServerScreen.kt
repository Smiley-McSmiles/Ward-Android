package org.ward.android.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ward.android.ConnectionState
import org.ward.android.data.AccountEntity
import org.ward.android.util.AppLockStore
import org.ward.android.xmpp.AdminCommand
import org.ward.android.xmpp.AdminField
import org.ward.android.xmpp.AdminStep
import org.ward.android.xmpp.AdminNodes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerTab(vm: AppViewModel, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>, pad: PaddingValues, onBack: () -> Unit = {}, onSwipeBack: (() -> Unit)? = null) {
    val commands by vm.core.adminCommands.collectAsState()
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 0L) }
    var stats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var users by remember { mutableStateOf<Pair<List<String>, String>>(emptyList<String>() to "") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var command by remember { mutableStateOf<AdminCommand?>(null) }
    var invite by remember { mutableStateOf<String?>(null) }
    var xml by remember { mutableStateOf(false) }
    var disco by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val acc = accounts.firstOrNull { it.id == accountId }
    val session = vm.core.session(accountId)
    val list = commands[accountId] ?: emptyList()
    suspend fun load() {
        val svc = session?.admin ?: return
        loading = true; error = ""
        try {
            withContext(Dispatchers.IO) {
                stats = svc.stats()
                users = svc.users()
            }
        } catch (e: Exception) { error = adminError(e) }
        loading = false
    }
    LaunchedEffect(accountId, refresh, states[accountId]?.state) {
        if (states[accountId]?.state == "online") load()
    }
    BackHandler(enabled = command == null && !xml && !disco) { onBack() }
    // The admin list stays composed under command/XML/discovery pages so swiping back reveals it.
    val serverPage: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(pad).statusBarsPadding().navigationBarsPadding()) {
            if (accounts.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        FilterChip(selected = a.id == accountId, onClick = { accountId = a.id },
                            label = { Text(a.domain) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                WardBackButton(onBack)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(acc?.domain ?: "Server", style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("Administration", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ServerActionButton(Icons.Filled.Refresh, "Refresh", enabled = !loading) { refresh++ }
                ServerActionButton(Icons.Filled.Dns, "Service discovery") { disco = true }
                ServerActionButton(Icons.Filled.Code, "XML console") { xml = true }
            }

            if (states[accountId]?.state != "online") {
                Spacer(Modifier.height(8.dp))
                SettingsGroup {
                    PanelListItem(
                        headlineContent = { Text("Connect to manage this server", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(states[accountId]?.error?.takeIf { it.isNotBlank() }
                            ?: "This account is offline. Connect it to load administration commands.") },
                        leadingContent = { SettingsIconTile(Icons.Filled.CloudOff, WardIconColors.orange) })
                    SettingsDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (acc != null) Button(onClick = { vm.connect(acc) }) { Text("Connect") }
                    }
                }
                return@Column
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }

            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 34.dp)) {
                if (error.isNotBlank()) {
                    item {
                        SettingsGroup {
                            PanelListItem(
                                headlineContent = { Text("Could not load administration", style = MaterialTheme.typography.titleMedium) },
                                supportingContent = { Text(error) },
                                leadingContent = { SettingsIconTile(Icons.Filled.ErrorOutline, WardIconColors.red) })
                        }
                    }
                }

                if (stats.isNotEmpty() || !loading) {
                item { SettingsSectionLabel("Overview", "Server statistics published to administrators") }
                item {
                    SettingsGroup {
                        if (stats.isEmpty() && !loading) {
                            PanelListItem(
                                headlineContent = { Text("No statistics", style = MaterialTheme.typography.titleMedium) },
                                supportingContent = { Text("The server shares no numbers, or this account is not an admin.") },
                                leadingContent = { SettingsIconTile(Icons.Filled.Analytics, WardIconColors.grey) })
                        } else {
                            stats.entries.toList().forEachIndexed { index, (key, value) ->
                                if (index > 0) SettingsDivider()
                                PanelListItem(
                                    headlineContent = { Text(statLabel(key), style = MaterialTheme.typography.titleMedium) },
                                    leadingContent = { SettingsIconTile(statIcon(key), statColor(key)) },
                                    trailingContent = {
                                        Text(value, style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    })
                            }
                        }
                    }
                }
                }

                item { SettingsSectionLabel("Invites") }
                item {
                    SettingsGroup {
                        PanelListItem(
                            headlineContent = { Text("Create account invite", style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("Generate a one-time link for a new account on this server") },
                            leadingContent = { SettingsIconTile(Icons.Filled.AddLink, WardIconColors.green) },
                            trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                            modifier = Modifier.clickable(enabled = !loading) {
                                scope.launch {
                                    try {
                                        val link = withContext(Dispatchers.IO) { session?.admin?.createInvite(false) ?: "" }
                                        if (link.isNotBlank()) invite = link
                                    } catch (e: Exception) { error = adminError(e) }
                                }
                            })
                        SettingsDivider()
                        PanelListItem(
                            headlineContent = { Text("How invites work", style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("Invite links let someone create an account on this server. Each link works once.") },
                            leadingContent = { SettingsIconTile(Icons.Filled.Link, WardIconColors.blue) })
                    }
                }

                if (users.first.isNotEmpty()) {
                    item { SettingsSectionLabel(if (users.second == "online") "Online now" else "Users") }
                    item {
                        SettingsGroup {
                            users.first.take(200).forEachIndexed { index, user ->
                                if (index > 0) SettingsDivider()
                                PanelListItem(
                                    headlineContent = { Text(user, style = MaterialTheme.typography.titleMedium) },
                                    leadingContent = { Avatar(user, 36) })
                            }
                        }
                    }
                }

                if (list.isNotEmpty() || !loading) {
                item { SettingsSectionLabel("Commands", "Everything the server lets administrators do") }
                item {
                    SettingsGroup {
                        if (list.isEmpty() && !loading) {
                            PanelListItem(
                                headlineContent = { Text("No commands available", style = MaterialTheme.typography.titleMedium) },
                                supportingContent = { Text("Prosody and ejabberd expose their administration here when this account is an admin.") },
                                leadingContent = { SettingsIconTile(Icons.Filled.Terminal, WardIconColors.grey) })
                        } else {
                            list.forEachIndexed { index, cmd ->
                                if (index > 0) SettingsDivider()
                                val prosody = cmd.node.contains("prosody")
                                PanelListItem(
                                    headlineContent = { Text(cmd.name, style = MaterialTheme.typography.titleMedium) },
                                    supportingContent = { Text(cmd.node, maxLines = 2) },
                                    leadingContent = {
                                        SettingsIconTile(if (prosody) Icons.Filled.Extension else Icons.Filled.Terminal,
                                            if (prosody) WardIconColors.purple else WardIconColors.blue)
                                    },
                                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                    modifier = Modifier.clickable { command = cmd })
                            }
                        }
                    }
                }
                }
            }
        }

        if (invite != null) {
            AlertDialog(onDismissRequest = { invite = null }, title = { Text("Invite") },
                text = { Column { Text("Send this link to the person you invite. It works once."); Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(invite!!, fontFamily = FontFamily.Monospace) } } },
                confirmButton = { TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Invite", invite))
                    invite = null
                }) { Text("Copy") } },
                dismissButton = { TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, invite) }
                    context.startActivity(Intent.createChooser(send, "Share invite"))
                }) { Text("Share") } })
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SwipeBack(onBack = onSwipeBack ?: onBack) { serverPage() }
        val activeCommand = command
        when {
            xml -> key("xml") {
                SwipeBack(onBack = { xml = false }, enterFromRight = true) {
                    XmlConsolePage(vm, onBack = { xml = false })
                }
            }
            disco -> key("disco") {
                SwipeBack(onBack = { disco = false }, enterFromRight = true) {
                    DiscoPage(vm, onBack = { disco = false })
                }
            }
            activeCommand != null -> key(activeCommand) {
                SwipeBack(onBack = { command = null; refresh++ }, enterFromRight = true) {
                    AdminCommandScreen(vm, accountId, activeCommand,
                        onBack = { command = null; refresh++ })
                }
            }
        }
    }
}

@Composable
private fun ServerActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                               enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = wardPanelColor(),
            contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.padding(start = 6.dp)) {
        Icon(icon, label)
    }
}

private fun statLabel(key: String): String = when (key) {
    "registeredusersnum" -> "Registered users"
    "onlineusersnum" -> "Online users"
    "activeusersnum" -> "Active users"
    "uptime" -> "Uptime"
    else -> key
}

private fun statIcon(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key) {
    "registeredusersnum" -> Icons.Filled.People
    "onlineusersnum" -> Icons.Filled.Person
    "activeusersnum" -> Icons.Filled.Bolt
    "uptime" -> Icons.Filled.Schedule
    else -> Icons.Filled.Analytics
}

private fun statColor(key: String): androidx.compose.ui.graphics.Color = when (key) {
    "registeredusersnum" -> WardIconColors.blue
    "onlineusersnum" -> WardIconColors.green
    "activeusersnum" -> WardIconColors.cyan
    "uptime" -> WardIconColors.orange
    else -> WardIconColors.grey
}

private fun adminError(e: Throwable): String {
    val raw = e.message.orEmpty()
    return when {
        raw.contains("no longer connected", true) || e is org.jivesoftware.smack.SmackException.NotConnectedException ->
            "The connection dropped while loading server information. Reconnect and try again."
        raw.contains("service-unavailable", true) ->
            "This server does not provide that administration feature."
        raw.contains("No response", true) || e is org.jivesoftware.smack.SmackException.NoResponseException ->
            "The server did not answer the administration request. Try again."
        e is org.jivesoftware.smack.XMPPException.XMPPErrorException -> {
            val condition = runCatching { e.stanzaError.condition.toString() }.getOrNull().orEmpty()
            "The server rejected the administration request" + if (condition.isBlank()) "." else " ($condition)."
        }
        raw.isBlank() -> "The server administration request failed."
        else -> raw.lineSequence().firstOrNull()?.take(220) ?: "The server administration request failed."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCommandScreen(vm: AppViewModel, accountId: Long, command: AdminCommand, onBack: () -> Unit) {
    BackHandler { onBack() }
    var step by remember { mutableStateOf<AdminStep?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val svc = vm.core.session(accountId)?.admin
    fun apply(s: AdminStep) {
        step = s
        values = s.fields.associate { it.varName to it.values }
    }
    LaunchedEffect(command) {
        busy = true
        try { withContext(Dispatchers.IO) { apply(svc?.execute(command.jid, command.node) ?: error("Not connected")) } }
        catch (e: Exception) { error = adminError(e) }
        busy = false
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(command.name, style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    FilledIconButton(onClick = { step?.let { svc?.cancel(it) }; onBack() }, shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = wardPanelColor(),
                            contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Filled.Close, "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(bottom = 30.dp)) {
            SettingsSectionLabel("Command")
            SettingsGroup {
                PanelListItem(
                    headlineContent = { Text(command.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(command.node, maxLines = 2) },
                    leadingContent = { SettingsIconTile(Icons.Filled.Terminal, WardIconColors.purple) })
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (error.isNotBlank()) {
                SettingsGroup {
                    PanelListItem(
                        headlineContent = { Text("Command failed", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(error) },
                        leadingContent = { SettingsIconTile(Icons.Filled.ErrorOutline, WardIconColors.red) })
                }
            }

            val s = step
            if (s != null) {
                if (s.title.isNotBlank() || s.instructions.isNotEmpty() || s.notes.isNotEmpty()) {
                    SettingsSectionLabel("Details")
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            if (s.title.isNotBlank()) {
                                Text(s.title, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(4.dp))
                            }
                            s.instructions.forEach { instruction ->
                                Text(instruction, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp))
                            }
                            s.notes.forEach { note ->
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (note.type == "error") MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(note.text, style = MaterialTheme.typography.bodyMedium,
                                        color = if (note.type == "error") MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }

                val visible = s.fields.filter { it.type != "hidden" }
                if (visible.isNotEmpty()) {
                    SettingsSectionLabel("Fields")
                    SettingsGroup {
                        visible.forEachIndexed { index, field ->
                            if (index > 0) SettingsDivider()
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                AdminFieldView(field, values[field.varName] ?: emptyList()) { value ->
                                    values = values + (field.varName to value)
                                }
                            }
                        }
                    }
                }

                if (s.reported.isNotEmpty() && s.items.isNotEmpty()) {
                    SettingsSectionLabel("Results")
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                s.reported.forEach { column ->
                                    Text(column.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            SettingsDivider()
                            s.items.forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    row.forEach { cell ->
                                        Text(cell, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (!s.finished) {
                    SettingsGroup {
                        Row(Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if ("prev" in s.actions) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { apply(svc!!.advance(s, "prev")) }
                                            .onFailure { error = it.message ?: "Failed" }
                                        busy = false
                                    }
                                }) { Text("Back") }
                            }
                            Button(onClick = {
                                val act = s.defaultAction.ifBlank { if ("next" in s.actions) "next" else "complete" }
                                scope.launch {
                                    busy = true; error = ""
                                    try { withContext(Dispatchers.IO) { apply(svc!!.advance(s, act, values)) } }
                                    catch (e: Exception) { error = adminError(e) }
                                    busy = false
                                }
                            }, enabled = !busy) {
                                Text(when (s.defaultAction) { "next" -> "Next"; "complete" -> "Finish"; "execute" -> "Run"; else -> "Finish" })
                            }
                            TextButton(onClick = {
                                scope.launch { busy = true; runCatching { svc?.cancel(s) }; busy = false; onBack() }
                            }) { Text("Cancel") }
                        }
                    }
                } else {
                    SettingsGroup {
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminFieldView(f: AdminField, values: List<String>, onChange: (List<String>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(f.label + if (f.required) " *" else "", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        if (f.desc.isNotBlank()) Text(f.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (f.media.isNotBlank()) {
            val bmp = remember(f.media) {
                runCatching {
                    val data = if (f.media.contains(",")) f.media.substringAfter(',') else f.media
                    BitmapFactory.decodeByteArray(Base64.decode(data, Base64.DEFAULT), 0, Base64.decode(data, Base64.DEFAULT).size)
                }.getOrNull()
            }
            if (bmp != null) Image(bmp.asImageBitmap(), "CAPTCHA", Modifier.padding(vertical = 6.dp).heightIn(max = 100.dp))
        }
        when (f.type) {
            "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = values.firstOrNull() == "1" || values.firstOrNull() == "true",
                    onCheckedChange = { onChange(listOf(if (it) "1" else "0")) })
            }
            "list-single" -> {
                var open by remember { mutableStateOf(false) }
                val label = f.options.firstOrNull { it.first == values.firstOrNull() }?.second ?: values.firstOrNull() ?: "Choose"
                Box { OutlinedButton(onClick = { open = true }) { Text(label) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        f.options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(listOf(v)); open = false }) }
                    }
                }
            }
            "list-multi" -> Column { f.options.forEach { (v, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = v in values, onCheckedChange = { c -> onChange(if (c) values + v else values - v) })
                    Text(l)
                }
            } }
            "text-multi", "jid-multi" -> OutlinedTextField(values.joinToString("\n"), { onChange(it.split('\n')) },
                modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text(if (f.type == "jid-multi") "One per line" else "Value") })
            "text-private" -> OutlinedTextField(values.firstOrNull() ?: "", { onChange(listOf(it)) },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            "fixed" -> Text(f.label, style = MaterialTheme.typography.bodyMedium)
            else -> OutlinedTextField(values.firstOrNull() ?: "", { onChange(listOf(it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XmlConsolePage(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val log by vm.xml.collectAsState()
    val accounts by vm.accounts.collectAsState()
    var accountId by remember { mutableStateOf(accounts.firstOrNull { vm.core.isOnline(it.id) }?.id ?: accounts.firstOrNull()?.id ?: 0L) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<org.ward.android.XmlLogEntry>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val filteredLog = (if (paused) frozen else log).filter { it.accountId == accountId }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        AppLockStore.endExternalActivity()
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val source = java.io.File(context.cacheDir, "xml-console-export.txt")
                    if (!source.exists()) error("Nothing to save")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open the selected file")
                    source.delete()
                }
            }.isSuccess
            android.widget.Toast.makeText(context, if (ok) "XML console saved" else "Could not save XML console",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(filteredLog.size, accountId, paused) {
        if (!paused && filteredLog.isNotEmpty()) {
            runCatching { listState.scrollToItem(filteredLog.lastIndex) }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(title = { Text("XML console", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = { WardBackButton(onBack) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background),
        actions = {
            IconButton(onClick = {
                paused = !paused
                frozen = if (paused) log else emptyList()
            }) { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause") }
            IconButton(onClick = {
                if (filteredLog.isEmpty()) {
                    android.widget.Toast.makeText(context, "Nothing to save", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val exportText = filteredLog.joinToString("\n\n") { (if (it.incoming) "← " else "→ ") + it.text }
                    scope.launch {
                        // The document picker can recreate the activity. Keep the data in a cache
                        // file so the result callback still has the log after recreation.
                        withContext(Dispatchers.IO) {
                            java.io.File(context.cacheDir, "xml-console-export.txt").writeText(exportText)
                        }
                        AppLockStore.beginExternalActivity()
                        saveLog.launch("ward-xml-${System.currentTimeMillis()}.txt")
                    }
                }
            }) { Icon(Icons.Filled.Save, "Save as") }
            IconButton(onClick = {
                vm.core.clearXml()
                frozen = emptyList()
            }) { Icon(Icons.Filled.DeleteSweep, "Clear") }
        }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).navigationBarsPadding()) {
            if (accounts.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.domain) }) }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                itemsIndexed(filteredLog, key = { i, e -> "$i:${e.time}:${e.text.hashCode()}" }) { i, e ->
                    Text((if (e.incoming) "← " else "→ ") + e.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (e.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                            context.copyText(e.text)
                            android.widget.Toast.makeText(context, "Stanza copied", android.widget.Toast.LENGTH_SHORT).show()
                        })
                }
            }
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    OutlinedTextField(text, { text = it }, label = { Text("Raw stanza XML") }, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            error = if (vm.core.sendRawXml(accountId, text)) "" else "Could not send the stanza"
                            text = ""
                        }) { Text("Send") }
                        if (error.isNotBlank()) {
                            Text(error, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoPage(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val accounts by vm.accounts.collectAsState()
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 0L) }
    var jid by remember { mutableStateOf(accounts.firstOrNull()?.domain ?: "") }
    var info by remember { mutableStateOf<org.jivesoftware.smackx.disco.packet.DiscoverInfo?>(null) }
    var items by remember { mutableStateOf<org.jivesoftware.smackx.disco.packet.DiscoverItems?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun query() {
        val s = vm.core.session(accountId) ?: return
        busy = true; error = ""
        vm.core.scope.launch {
            try {
                withContext(Dispatchers.IO) { info = s.discoverInfo(jid); items = s.discoverItems(jid) }
            } catch (e: Exception) { error = e.message ?: "Could not query the service" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { query() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Service discovery", style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = { WardBackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(bottom = 30.dp)) {
            SettingsSectionLabel("Query")
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(jid, { jid = it }, label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { query() }, enabled = !busy) { Text("Query") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            if (error.isNotBlank()) {
                SettingsGroup {
                    PanelListItem(
                        headlineContent = { Text("Could not query the service", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(error) },
                        leadingContent = { SettingsIconTile(Icons.Filled.ErrorOutline, WardIconColors.red) })
                }
            }
            val discoverInfo: org.jivesoftware.smackx.disco.packet.DiscoverInfo? = info
            if (discoverInfo != null) {
                SettingsSectionLabel("Identities")
                SettingsGroup {
                    val identities = discoverInfo.identities
                    if (identities.isEmpty()) {
                        PanelListItem(
                            headlineContent = { Text("No identities reported", style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("The server did not describe what this address is.") },
                            leadingContent = { SettingsIconTile(Icons.Filled.Info, WardIconColors.grey) })
                    } else {
                        identities.forEachIndexed { index, identity ->
                            if (index > 0) SettingsDivider()
                            PanelListItem(
                                headlineContent = { Text("${identity.category} / ${identity.type}",
                                    style = MaterialTheme.typography.titleMedium) },
                                supportingContent = if (identity.name.isNotBlank()) ({
                                    Text(identity.name)
                                }) else null,
                                leadingContent = { SettingsIconTile(Icons.Filled.Info, WardIconColors.blue) })
                        }
                    }
                }

                SettingsSectionLabel("Features")
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val features = discoverInfo.features.map { it.getVar() }.sorted()
                        if (features.isEmpty()) {
                            Text("No features reported", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            features.forEach { feature ->
                                Text(feature, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 3.dp))
                            }
                        }
                    }
                }
            }
            items?.let { itemsResult ->
                SettingsSectionLabel("Items")
                SettingsGroup {
                    if (itemsResult.items.isEmpty()) {
                        PanelListItem(
                            headlineContent = { Text("No items reported", style = MaterialTheme.typography.titleMedium) },
                            leadingContent = { SettingsIconTile(Icons.Filled.Dns, WardIconColors.grey) })
                    } else {
                        itemsResult.items.forEachIndexed { index, item ->
                            if (index > 0) SettingsDivider()
                            PanelListItem(
                                headlineContent = { Text(item.entityID.toString(), style = MaterialTheme.typography.titleMedium) },
                                supportingContent = item.node?.let { node -> { Text(node) } },
                                leadingContent = { SettingsIconTile(Icons.Filled.Dns, WardIconColors.teal) },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                modifier = Modifier.clickable {
                                    jid = item.entityID.toString()
                                    query()
                                })
                        }
                    }
                }
            }
        }
    }
}
