package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ward.android.ConnectionState
import org.ward.android.data.*
import org.ward.android.util.SecretStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: AppViewModel, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>,
                pad: PaddingValues, onAddAccount: () -> Unit, onBack: (() -> Unit)? = null,
                initialPage: String? = null) {
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var search by remember { mutableStateOf("") }
    val settings by vm.settings.state.collectAsState()
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    if (page != null) {
        SettingsPage(vm, page!!, accounts, states, onBack = { page = null }, onNavigate = { page = it }, onAddAccount = onAddAccount)
        return
    }
    Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text("Search preferences") },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true, shape = androidx.compose.foundation.shape.CircleShape)
        LazyColumn {
            item {
                Text("Accounts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 10.dp),
                    color = MaterialTheme.colorScheme.primary)
            }
            items(accounts, key = { it.id }) { acc ->
                ListItem(headlineContent = { Text(acc.displayName) }, supportingContent = {
                    Text(acc.jid + " · " + (states[acc.id]?.state ?: "offline"))
                }, leadingContent = { Avatar(acc.displayName, 40, acc.color,
                    show = if (states[acc.id]?.state == "online") "available" else "offline") },
                    trailingContent = { TextButton(onClick = { vm.connect(acc) }) { Text(if (states[acc.id]?.state == "online") "Online" else "Connect") } },
                    modifier = Modifier.clickable { page = "account:${acc.id}" })
            }
            item {
                ListItem(headlineContent = { Text("Add account") }, leadingContent = { Icon(Icons.Filled.PersonAdd, null) },
                    modifier = Modifier.clickable { onAddAccount() })
                HorizontalDivider()
                Text("Preferences", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 10.dp),
                    color = MaterialTheme.colorScheme.primary)
            }
            val pages = SettingsSchema.pages
            items(pages, key = { it.first }) { (key, title) ->
                val match = search.isBlank() || SettingsSchema.specs.any { it.page == key && (it.title.contains(search, true) || it.subtitle.contains(search, true)) }
                if (match) ListItem(headlineContent = { Text(title) },
                    leadingContent = { Icon(pageIcon(key), null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.clickable { page = key })
            }
            item {
                ListItem(headlineContent = { Text("Donate") }, supportingContent = { Text("Support Ward’s development") },
                    leadingContent = { Icon(Icons.Filled.Favorite, null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.clickable { page = "donate" })
            }
            item { Spacer(Modifier.height(20.dp)); Text("Ward 1.0.3 · XMPP, OMEMO and Markdown", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

fun pageIcon(key: String) = when (key) {
    "general" -> Icons.Filled.Tune; "appearance" -> Icons.Filled.Palette; "chats" -> Icons.Filled.Chat
    "calls" -> Icons.Filled.Call; "attachments" -> Icons.Filled.AttachFile
    "notifications" -> Icons.Filled.Notifications; "accounts" -> Icons.Filled.People
    "security" -> Icons.Filled.Lock; "encryption" -> Icons.Filled.Lock; "storage" -> Icons.Filled.Storage; else -> Icons.Filled.Build
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(vm: AppViewModel, key: String, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>, onBack: () -> Unit, onNavigate: (String) -> Unit, onAddAccount: () -> Unit = {}) {
    BackHandler { onBack() }
    if (key.startsWith("account:")) {
        AccountSettingsPage(vm, key.substringAfter(':').toLong(), accounts, states, onBack)
        return
    }
    if (key == "about") { AboutPage(onBack); return }
    if (key == "donate") { DonatePage(onBack); return }
    if (key == "security") { SecurityPage(vm, onBack); return }
    if (key == "xml") { XmlConsolePage(vm, onBack); return }
    if (key == "disco") { DiscoPage(vm, onBack); return }
    val state by vm.settings.state.collectAsState()
    var repairResult by remember { mutableStateOf<String?>(null) }
    val specs = SettingsSchema.specs.filter { it.page == key }
    val groups = specs.groupBy { it.group }
    Scaffold(topBar = { TopAppBar(title = { Text(SettingsSchema.pages.firstOrNull { it.first == key }?.second ?: key) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 30.dp)) {
            if (key == "calls") {
                item { ListItem(headlineContent = { Text("Audio and video calls") },
                    supportingContent = { Text("Calls use Jingle signaling and WebRTC media. STUN/TURN is discovered from the server when available.") },
                    leadingContent = { Icon(Icons.Filled.Call, null) }) }
            }
            if (key == "accounts") {
                item { AccountListSettings(vm, accounts, states, onAddAccount) }
            }
            for ((group, list) in groups) {
                item { Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
                items(list, key = { it.key }) { spec ->
                    SettingRow(vm, spec, state[spec.key] ?: spec.default.toString())
                }
            }
            if (key == "advanced") {
                item {
                    ListItem(headlineContent = { Text("XML console") }, supportingContent = { Text("Watch and send raw stanzas") },
                        leadingContent = { Icon(Icons.Filled.Code, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                        modifier = Modifier.clickable { onNavigate("xml") })
                }
                item { ListItem(headlineContent = { Text("Service discovery") }, leadingContent = { Icon(Icons.Filled.Dns, null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) }, modifier = Modifier.clickable { onNavigate("disco") }) }
            }
            if (key == "encryption") {
                item {
                    ListItem(headlineContent = { Text("Repair OMEMO 2") }, supportingContent = {
                        Text("Rebuild this account's OMEMO 2 keys and publish a fresh bundle. Contact sessions rebuild automatically.")
                    }, leadingContent = { Icon(Icons.Filled.Build, null) },
                        modifier = Modifier.clickable { vm.repairOmemo2 { repairResult = it } })
                }
            }
            if (key == "general") {
                item { ListItem(headlineContent = { Text("About Ward") }, leadingContent = { Icon(Icons.Filled.Info, null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) }, modifier = Modifier.clickable { onNavigate("about") }) }
            }
        }
    }
    if (repairResult != null) {
        AlertDialog(onDismissRequest = { repairResult = null },
            title = { Text("OMEMO 2 repair") },
            text = { Text(repairResult!!) },
            confirmButton = { TextButton(onClick = { repairResult = null }) { Text("OK") } })
    }
}

@Composable
fun SettingRow(vm: AppViewModel, spec: SettingSpec, value: String) {
    when (spec.type) {
        SettingType.BOOL -> ListItem(
            headlineContent = { Text(spec.title) }, supportingContent = if (spec.subtitle.isBlank()) null else ({ Text(spec.subtitle) }),
            trailingContent = { Switch(checked = value.toBoolean(), onCheckedChange = { vm.settings.set(spec.key, it) }) })
        SettingType.CHOICE -> {
            var open by remember { mutableStateOf(false) }
            val label = spec.choices.firstOrNull { it.value.toString() == value }?.label ?: value
            ListItem(headlineContent = { Text(spec.title) },
                supportingContent = if (spec.subtitle.isBlank()) null else ({ Text(spec.subtitle) }),
                trailingContent = {
                    Box {
                        TextButton(onClick = { open = true }) { Text(label) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for (ch in spec.choices) DropdownMenuItem(text = { Text(ch.label) },
                                onClick = { vm.settings.set(spec.key, ch.value); open = false })
                        }
                    }
                })
        }
        SettingType.INT -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row { Text(spec.title, modifier = Modifier.weight(1f)); Text("$value${spec.unit}") }
            Slider(value = value.toFloatOrNull() ?: 0f,
                onValueChange = { vm.settings.set(spec.key, it.toInt()) },
                valueRange = (spec.min ?: 0).toFloat()..(spec.max ?: 100).toFloat(),
                steps = ((spec.max ?: 100) - (spec.min ?: 0)) / spec.step.coerceAtLeast(1) - 1)
            if (spec.subtitle.isNotBlank()) Text(spec.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingType.TEXT, SettingType.LIST, SettingType.TIME_RANGE -> {
            var dialog by remember { mutableStateOf(false) }
            ListItem(headlineContent = { Text(spec.title) },
                supportingContent = { Text(if (value.isBlank()) (spec.subtitle.ifBlank { "Default" }) else value.take(80),
                    maxLines = 2) },
                trailingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { dialog = true })
            if (dialog) {
                var text by remember { mutableStateOf(value) }
                AlertDialog(onDismissRequest = { dialog = false }, title = { Text(spec.title) },
                    text = { OutlinedTextField(text, { text = it }, minLines = 3, label = { Text(spec.subtitle.ifBlank { "Value" }) }) },
                    confirmButton = { TextButton(onClick = { vm.settings.set(spec.key, text); dialog = false }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } })
            }
        }
    }
}

@Composable
fun AccountListSettings(vm: AppViewModel, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>, onAddAccount: () -> Unit) {
    Column {
        for (acc in accounts) ListItem(headlineContent = { Text(acc.displayName) }, supportingContent = { Text(acc.jid) },
            leadingContent = { Avatar(acc.displayName, 40, acc.color, photo = acc.avatar) },
            trailingContent = { Text(states[acc.id]?.state ?: "offline") })
        TextButton(onClick = onAddAccount) { Text("Add account") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsPage(vm: AppViewModel, id: Long, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>, onBack: () -> Unit) {
    val acc = accounts.firstOrNull { it.id == id }
    var nick by remember { mutableStateOf(acc?.nickname ?: "") }
    var password by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    var pgpFingerprint by remember { mutableStateOf("") }
    var pgpBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (acc == null) { onBack(); return }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        org.ward.android.util.AppLockStore.endExternalActivity()
        if (uri != null) {
            runCatching {
                val file = File(context.filesDir, "profile-${acc.id}-${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                val bytes = file.readBytes()
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                vm.core.scope.launch {
                    vm.core.updateAccount(acc.copy(avatar = android.net.Uri.fromFile(file).toString()))
                    vm.core.setAccountOption(acc, "avatar_dirty", "true")
                    vm.core.session(acc.id)?.publishAvatar(bytes, mime)
                }
            }
        }
    }
    LaunchedEffect(acc.id) { pgpFingerprint = vm.core.accountOption(acc, "pgp_key") }
    val state = states[acc.id]
    Scaffold(topBar = { TopAppBar(title = { Text(acc.jid) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("Connection") }, supportingContent = {
                Text(if (state?.state == "online") "Connected" else state?.error?.ifBlank { state.state } ?: "Offline")
            }, leadingContent = { Icon(Icons.Filled.Wifi, null) })
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state?.state == "online") OutlinedButton(onClick = { vm.disconnect(acc) }) { Text("Disconnect") }
                else Button(onClick = { vm.connect(acc) }) { Text("Connect") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Profile", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(acc.displayName, 76, acc.color, photo = acc.avatar)
                Spacer(Modifier.width(16.dp))
                Column {
                    Button(onClick = {
                        org.ward.android.util.AppLockStore.beginExternalActivity()
                        pickPhoto.launch("image/*")
                    }) { Text("Choose photo") }
                    Text("Published with your XMPP profile", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(nick, { nick = it }, label = { Text("Nickname") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = {
                    vm.core.scope.launch {
                        vm.core.updateAccount(acc.copy(nickname = nick))
                        vm.core.session(acc.id)?.publishNick(nick)
                    }
                }) { Text("Save name") }
            }
            ListItem(
                headlineContent = { Text("Share contact QR code") },
                supportingContent = { Text("Let someone scan this address to add you") },
                leadingContent = { Icon(Icons.Filled.QrCode2, null) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                modifier = Modifier.clickable { qrOpen = true })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Security", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 8.dp))
            OutlinedTextField(password, { password = it }, label = { Text("New password") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.padding(16.dp)) {
                Button(onClick = {
                    if (password.length >= 6) {
                        vm.core.scope.launch {
                            runCatching {
                                val conn = vm.core.session(acc.id)?.connection ?: return@launch
                                org.jivesoftware.smackx.iqregister.AccountManager.getInstance(conn).changePassword(password)
                                vm.core.updateAccount(acc.copy(encryptedPassword = SecretStore.encrypt(password)))
                            }
                            password = ""
                        }
                    }
                }) { Text("Change password") }
            }
            ListItem(headlineContent = { Text("OpenPGP key") },
                supportingContent = { Text(if (pgpFingerprint.isBlank()) "No key yet; one is generated on first use" else pgpFingerprint) },
                leadingContent = { Icon(Icons.Filled.VpnKey, null) },
                trailingContent = { TextButton(onClick = {
                    pgpBusy = true
                    vm.core.scope.launch {
                        val secret = org.ward.android.xmpp.OpenPgp.ensureKey(vm.core, acc)
                        val fp = org.ward.android.xmpp.OpenPgp.fingerprint(secret)
                        vm.core.setAccountOption(acc, "pgp_key", fp)
                        vm.core.dao.insertPgpKey(org.ward.android.data.PgpKeyEntity(acc.id, acc.jid, fp,
                            org.ward.android.xmpp.OpenPgp.publicArmor(secret), name = acc.displayName, trust = "verified"))
                        pgpFingerprint = fp; pgpBusy = false
                        vm.core.session(acc.id)?.ensurePgp()
                    }
                }, enabled = !pgpBusy) { Text(if (pgpFingerprint.isBlank()) "Generate" else "Republish") } })
            ListItem(headlineContent = { Text("Devices and keys") },
                supportingContent = { Text("Fingerprints and verification for your own OMEMO devices") },
                leadingContent = { Icon(Icons.Filled.Key, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                modifier = Modifier.clickable { showDevices = true })
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            ListItem(headlineContent = { Text("Remove account from Ward…", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmRemove = true })
            Spacer(Modifier.height(30.dp))
        }
    }
    if (showDevices) DevicesSheet(vm, acc.id, acc.jid, own = true, onDismiss = { showDevices = false })
    if (qrOpen) QrCodeDialog("Scan to add ${acc.displayName}", "xmpp:${acc.jid}", onDismiss = { qrOpen = false })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove ${acc.jid}?") },
        text = { Text("Ward forgets this account and its local history. The account itself keeps existing on the server.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; vm.deleteAccount(acc); onBack() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonatePage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun copy(label: String, value: String) {
        context.copyText(value)
        android.widget.Toast.makeText(context, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Donate") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Ward is free and open source. If you would like to support its development, you can send a donation to one of these addresses.",
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text("Tap an address to copy it to the clipboard.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            DonationRow("Bitcoin (BTC)", "bc1qy2gtdhnfxp9dcs6v9jda748npmsjx3jgwp99mx") { copy("Bitcoin address", it) }
            DonationRow("Monero (XMR)", "82xtMVSmesuLjPtgHfBCEhM5Fpqh1SLLNf9pzHRRNPqQZsvrnmoM1ZGC7AiLyPfsufdyrMWHrWYV2hsC8jc5rEBVLMHWTLy") { copy("Monero address", it) }
            DonationRow("Cash App", "\$SmileyMcSmiles") { copy("Cash App tag", it) }
            Spacer(Modifier.height(20.dp))
            Text("Thank you for helping keep Ward independent.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DonationRow(label: String, value: String, onCopy: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(value, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onCopy(value) }) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("About Ward") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar("Ward", 96, "#3584e4")
            Spacer(Modifier.height(14.dp))
            Text("Ward", style = MaterialTheme.typography.headlineMedium)
            Text("1.0.3 for Android", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Private messaging on the open XMPP network, encrypted by default with OMEMO. Markdown messages, groups, calls, files, and a native server administration console for Prosody and ejabberd.",
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text("MIT licensed. OMEMO uses the Signal protocol. Adapted from Ward for GTK by Smiley McSmiles.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
