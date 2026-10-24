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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
fun ServerTab(vm: AppViewModel, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>, pad: PaddingValues, onBack: () -> Unit = {}) {
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
        } catch (e: Exception) { error = e.message ?: "Could not load server information" }
        loading = false
    }
    LaunchedEffect(accountId, refresh, states[accountId]?.state) {
        if (states[accountId]?.state == "online") load()
    }
    if (xml) { XmlConsolePage(vm, onBack = { xml = false }); return }
    if (disco) { DiscoPage(vm, onBack = { disco = false }); return }
    if (command != null) {
        AdminCommandScreen(vm, accountId, command!!, onBack = { command = null; refresh++ })
        return
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
        if (accounts.size > 1) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                ScrollableTabRow(selectedTabIndex = accounts.indexOfFirst { it.id == accountId }.coerceAtLeast(0), edgePadding = 0.dp) {
                    accounts.forEach { a -> Tab(selected = a.id == accountId, onClick = { accountId = a.id },
                        text = { Text(a.domain) }) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(acc?.domain ?: "Server", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Administration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(onClick = { disco = true }) { Icon(Icons.Filled.Dns, "Service discovery") }
            IconButton(onClick = { xml = true }) { Icon(Icons.Filled.Code, "XML console") }
        }
        if (states[accountId]?.state != "online") {
            ListItem(headlineContent = { Text("Connect to see server administration") },
                supportingContent = { Text(states[accountId]?.error ?: "This account is offline") },
                leadingContent = { Icon(Icons.Filled.CloudOff, null) })
            if (acc != null) Button(onClick = { vm.connect(acc) }, modifier = Modifier.padding(16.dp)) { Text("Connect") }
            return@Column
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Text("Overview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 10.dp))
            }
            if (stats.isEmpty() && !loading) item {
                ListItem(headlineContent = { Text("No statistics") },
                    supportingContent = { Text("The server shares no numbers, or this account is not an admin") },
                    leadingContent = { Icon(Icons.Filled.Info, null) })
            }
            items(stats.entries.toList(), key = { it.key }) { (k, v) ->
                val label = when (k) { "registeredusersnum" -> "Registered users"; "onlineusersnum" -> "Online users";
                    "activeusersnum" -> "Active users"; "uptime" -> "Uptime"; else -> k }
                ListItem(headlineContent = { Text(label) }, supportingContent = { Text(v, fontWeight = FontWeight.Medium) })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Invites", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val link = withContext(Dispatchers.IO) { session?.admin?.createInvite(false) ?: "" }
                                if (link.isNotBlank()) invite = link
                            } catch (e: Exception) { error = e.message ?: "Could not create an invite" }
                        }
                    }) { Icon(Icons.Filled.AddLink, null); Spacer(Modifier.width(4.dp)); Text("Create account invite") }
                }
                ListItem(headlineContent = { Text("Invite links let someone create an account on this server") },
                    leadingContent = { Icon(Icons.Filled.Link, null) })
            }
            if (users.first.isNotEmpty()) {
                item { Text(if (users.second == "online") "Online now" else "Users", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 10.dp)) }
                itemsIndexed(users.first.take(200), key = { i, u -> "$i:$u" }) { i, u -> ListItem(headlineContent = { Text(u) },
                    leadingContent = { Avatar(u, 34) }) }
            }
            item {
                Text("Commands", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 10.dp))
                Text("Everything the server lets administrators do", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (list.isEmpty() && !loading) item {
                ListItem(headlineContent = { Text("No commands are available to this account") },
                    supportingContent = { Text("Prosody and ejabberd expose their administration here when this account is an admin") },
                    leadingContent = { Icon(Icons.Filled.Terminal, null) })
            }
            items(list, key = { it.node + it.jid }) { cmd ->
                ListItem(headlineContent = { Text(cmd.name) }, supportingContent = { Text(cmd.node) },
                    leadingContent = { Icon(if (cmd.node.contains("prosody")) Icons.Filled.Extension else Icons.Filled.Terminal, null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.clickable { command = cmd })
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
        catch (e: Exception) { error = e.message ?: "The command failed" }
        busy = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(command.name) }, navigationIcon = {
        IconButton(onClick = { step?.let { svc?.cancel(it) }; onBack() }) { Icon(Icons.Filled.Close, null) }
    }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp)) {
            command.node.takeIf { it.contains("prosody") || it.startsWith("urn:") }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (error.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            val s = step
            if (s != null) {
                if (s.title.isNotBlank()) Text(s.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                s.instructions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                for (note in s.notes) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(
                        containerColor = if (note.type == "error") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(note.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val visible = s.fields.filter { it.type != "hidden" }
                if (visible.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            for (f in visible) AdminFieldView(f, values[f.varName] ?: emptyList()) { v -> values = values + (f.varName to v) }
                        }
                    }
                }
                if (s.reported.isNotEmpty() && s.items.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Row { s.reported.forEach { Text(it.label, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp) } }
                            HorizontalDivider()
                            s.items.forEach { row ->
                                Row { row.forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp) } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (!s.finished) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if ("prev" in s.actions) OutlinedButton(onClick = {
                            scope.launch { busy = true; runCatching { apply(svc!!.advance(s, "prev")) }.onFailure { error = it.message ?: "Failed" }; busy = false }
                        }) { Text("Back") }
                        Button(onClick = {
                            val act = s.defaultAction.ifBlank { if ("next" in s.actions) "next" else "complete" }
                            scope.launch {
                                busy = true; error = ""
                                try { withContext(Dispatchers.IO) { apply(svc!!.advance(s, act, values)) } }
                                catch (e: Exception) { error = e.message ?: "The command failed" }
                                busy = false
                            }
                        }, enabled = !busy) {
                            Text(when (s.defaultAction) { "next" -> "Next"; "complete" -> "Finish"; "execute" -> "Run"; else -> "Finish" })
                        }
                        TextButton(onClick = { scope.launch { busy = true; runCatching { svc?.cancel(s) }; busy = false; onBack() } }) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    if (s.notes.isEmpty() && s.fields.isEmpty()) Text("Done", Modifier.padding(16.dp))
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
    var pendingSave by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val filteredLog = (if (paused) frozen else log).filter { it.accountId == accountId }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        AppLockStore.endExternalActivity()
        if (uri == null) return@rememberLauncherForActivityResult
        val text = pendingSave
        pendingSave = ""
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("Could not open the selected file")
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
    Scaffold(topBar = { TopAppBar(title = { Text("XML console") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
        actions = {
            IconButton(onClick = {
                paused = !paused
                frozen = if (paused) log else emptyList()
            }) { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause") }
            IconButton(onClick = {
                if (filteredLog.isEmpty()) {
                    android.widget.Toast.makeText(context, "Nothing to save", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pendingSave = filteredLog.joinToString("\n\n") { (if (it.incoming) "← " else "→ ") + it.text }
                    AppLockStore.beginExternalActivity()
                    saveLog.launch("ward-xml-${System.currentTimeMillis()}.txt")
                }
            }) { Icon(Icons.Filled.Save, "Save as") }
            IconButton(onClick = {
                vm.core.clearXml()
                frozen = emptyList()
            }) { Icon(Icons.Filled.DeleteSweep, "Clear") }
        }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (accounts.size > 1) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.domain) }) }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(filteredLog, key = { i, e -> "$i:${e.time}:${e.text.hashCode()}" }) { i, e ->
                    Text((if (e.incoming) "← " else "→ ") + e.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (e.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                            context.copyText(e.text)
                            android.widget.Toast.makeText(context, "Stanza copied", android.widget.Toast.LENGTH_SHORT).show()
                        })
                }
            }
            OutlinedTextField(text, { text = it }, label = { Text("Raw stanza XML") }, minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(8.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Button(onClick = { error = if (vm.core.sendRawXml(accountId, text)) "" else "Could not send the stanza"; text = "" }) { Text("Send") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 10.dp))
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
    Scaffold(topBar = { TopAppBar(title = { Text("Service discovery") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp)) {
            OutlinedTextField(jid, { jid = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(Modifier.padding(vertical = 6.dp)) { Button(onClick = { query() }, enabled = !busy) { Text("Query") } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            val discoverInfo: org.jivesoftware.smackx.disco.packet.DiscoverInfo? = info
            if (discoverInfo != null) {
                Text("Identities", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp))
                for (id in discoverInfo.identities) {
                    ListItem(headlineContent = { Text("${id.category} / ${id.type}") },
                        supportingContent = { if (id.name.isNotBlank()) Text(id.name) })
                }
                Text("Features", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp))
                for (f in discoverInfo.features.map { it.getVar() }.sorted()) {
                    Text(f, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            items?.let { i ->
                Text("Items", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp))
                i.items.forEach { it2 ->
                    ListItem(headlineContent = { Text(it2.entityID.toString()) },
                        supportingContent = it2.node?.let { n -> { Text(n) } },
                        modifier = Modifier.clickable { jid = it2.entityID.toString(); query() })
                }
            }
        }
    }
}
