package org.ward.android.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ward.android.BuildConfig
import org.ward.android.ConnectionState
import org.ward.android.data.*
import org.ward.android.updates.UpdateCheck
import org.ward.android.updates.UpdateInfo
import org.ward.android.util.SecretStore
import java.io.File

private fun connectionLabel(state: ConnectionState?): String = when (state?.state) {
    "online" -> "Online"
    "connecting" -> "Connecting…"
    "error" -> state.error.ifBlank { "Connection error" }
    else -> "Offline"
}

@Composable
fun SettingsTab(vm: AppViewModel, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>,
                pad: PaddingValues, onAddAccount: () -> Unit, onBack: (() -> Unit)? = null,
                initialPage: String? = null) {
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var search by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    if (page != null) {
        SwipeBack(onBack = { page = null }) {
            SettingsPage(vm, page!!, accounts, states, onBack = { page = null }, onNavigate = { page = it },
                onAddAccount = onAddAccount)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) WardBackButton(onBack)
                Text("Settings", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = if (onBack == null) 16.dp else 0.dp))
            }
            OutlinedTextField(search, { search = it },
                placeholder = { Text("Search settings",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (search.isBlank()) null else ({
                    IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                }),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = true, shape = MaterialTheme.shapes.extraLarge)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp)) {
                if (search.isNotBlank()) {
                    val results = SettingsSchema.specs.filter {
                        it.title.contains(search, true) || it.subtitle.contains(search, true) ||
                            (SettingsSchema.pages.firstOrNull { p -> p.first == it.page }?.second
                                ?.contains(search, true) == true)
                    }
                    item { SettingsSectionLabel("Results", "${results.size} matching setting${if (results.size == 1) "" else "s"}") }
                    item {
                        SettingsGroup {
                            if (results.isEmpty()) {
                                PanelListItem(headlineContent = { Text("No matching settings") },
                                    supportingContent = { Text("Try a different word, like “TURN”, “theme” or “notifications”.") },
                                    leadingContent = { Icon(Icons.Filled.SearchOff, null) })
                            } else {
                                results.forEachIndexed { index, spec ->
                                    if (index > 0) SettingsDivider()
                                    PanelListItem(
                                        headlineContent = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                                        supportingContent = {
                                            Text(SettingsSchema.pages.firstOrNull { it.first == spec.page }?.second ?: spec.page)
                                        },
                                        leadingContent = { SettingsIconTile(pageIcon(spec.page), pageIconColor(spec.page)) },
                                        trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                        modifier = Modifier.clickable {
                                            search = ""
                                            page = spec.page
                                        })
                                }
                            }
                        }
                    }
                } else {
                    item {
                        SettingsGroup {
                            if (accounts.isEmpty()) {
                                PanelListItem(headlineContent = { Text("No accounts yet", style = MaterialTheme.typography.titleMedium) },
                                    supportingContent = { Text("Add an XMPP account to start messaging.") },
                                    leadingContent = { SettingsIconTile(Icons.Filled.PersonAdd, WardIconColors.green) },
                                    modifier = Modifier.clickable { onAddAccount() })
                            } else {
                                accounts.forEachIndexed { index, acc ->
                                    if (index > 0) SettingsDivider()
                                    val conn = states[acc.id]
                                    PanelListItem(
                                        headlineContent = { Text(acc.displayName, style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            Text(acc.jid + " · " + connectionLabel(conn), maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                        },
                                        leadingContent = {
                                            Avatar(acc.displayName, 40, acc.color, photo = acc.avatar,
                                                show = if (conn?.state == "online") "available" else "offline")
                                        },
                                        trailingContent = {
                                            if (conn?.state == "online") {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(Modifier.width(5.dp))
                                                    Text("Online", style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary)
                                                    Icon(Icons.Filled.ChevronRight, null)
                                                }
                                            } else {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    FilledTonalButton(onClick = {
                                                        scope.launch {
                                                            vm.connect(acc)
                                                            snackbar.showSnackbar("Connecting ${acc.displayName}…")
                                                        }
                                                    }) { Text("Connect") }
                                                    Icon(Icons.Filled.ChevronRight, null)
                                                }
                                            }
                                        },
                                        modifier = Modifier.clickable { page = "account:${acc.id}" })
                                }
                                SettingsDivider()
                                PanelListItem(headlineContent = { Text("Add account", style = MaterialTheme.typography.titleMedium) },
                                    leadingContent = { SettingsIconTile(Icons.Filled.PersonAdd, WardIconColors.green) },
                                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                    modifier = Modifier.clickable { onAddAccount() })
                            }
                        }
                    }

                    val preferenceGroups = listOf(
                        listOf("general", "appearance"),
                        listOf("chats", "calls", "attachments"),
                        listOf("notifications", "encryption", "privacy", "security"),
                        listOf("storage", "advanced")
                    )
                    for (group in preferenceGroups) {
                        item {
                            SettingsGroup {
                                group.forEachIndexed { index, key ->
                                    val title = SettingsSchema.pages.firstOrNull { it.first == key }?.second ?: key
                                    if (index > 0) SettingsDivider()
                                    PanelListItem(
                                        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
                                        supportingContent = pageSubtitle(key)?.let { subtitle -> { Text(subtitle) } },
                                        leadingContent = { SettingsIconTile(pageIcon(key), pageIconColor(key)) },
                                        trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                        modifier = Modifier.clickable { page = key })
                                }
                            }
                        }
                    }
                    item {
                        SettingsGroup {
                            PanelListItem(headlineContent = { Text("About Ward", style = MaterialTheme.typography.titleMedium) },
                                supportingContent = { Text("Version, licences and project links") },
                                leadingContent = { SettingsIconTile(Icons.Filled.Info, WardIconColors.blue) },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                modifier = Modifier.clickable { page = "about" })
                            SettingsDivider()
                            PanelListItem(headlineContent = { Text("Donate", style = MaterialTheme.typography.titleMedium) },
                                supportingContent = { Text("Support Ward’s development") },
                                leadingContent = { SettingsIconTile(Icons.Filled.Favorite, WardIconColors.pink) },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                                modifier = Modifier.clickable { page = "donate" })
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                            Text("Ward ${BuildConfig.VERSION_NAME} for Android", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("XMPP · OMEMO · Markdown", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        WardSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun pageSubtitle(key: String): String? = when (key) {
    "general" -> "Background, startup and battery"
    "appearance" -> "Theme, text, messages and chat list"
    "chats" -> "Writing, reactions and sharing"
    "calls" -> "Audio, relay servers and ringtones"
    "attachments" -> "Compression and automatic downloads"
    "notifications" -> "Content, highlighting, quiet hours and sounds"
    "encryption" -> "OMEMO, OpenPGP, devices and keys"
    "privacy" -> "Photo metadata and messages from strangers"
    "security" -> "App lock, screen privacy and failed-attempt protection"
    "accounts" -> "Accounts, photos and status"
    "storage" -> "Cache size and downloaded files"
    "advanced" -> "Connection timings and diagnostics"
    else -> null
}

@Composable
fun SettingsSectionLabel(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 14.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun pageIcon(key: String) = when (key) {
    "general" -> Icons.Filled.Tune
    "appearance" -> Icons.Filled.Palette
    "chats" -> Icons.Filled.Chat
    "calls" -> Icons.Filled.Call
    "attachments" -> Icons.Filled.AttachFile
    "notifications" -> Icons.Filled.Notifications
    "accounts" -> Icons.Filled.People
    "security" -> Icons.Filled.Lock
    "encryption" -> Icons.Filled.Security
    "privacy" -> Icons.Filled.VisibilityOff
    "storage" -> Icons.Filled.Storage
    "advanced" -> Icons.Filled.Tune
    else -> Icons.Filled.Settings
}

fun pageIconColor(key: String): Color = when (key) {
    "general" -> WardIconColors.blue
    "appearance" -> WardIconColors.orange
    "chats" -> WardIconColors.green
    "calls" -> WardIconColors.cyan
    "attachments" -> WardIconColors.yellow
    "notifications" -> WardIconColors.pink
    "encryption" -> WardIconColors.purple
    "privacy" -> WardIconColors.teal
    "security" -> WardIconColors.green
    "accounts" -> WardIconColors.blue
    "storage" -> WardIconColors.yellow
    "advanced" -> WardIconColors.grey
    "about" -> WardIconColors.blue
    "donate" -> WardIconColors.pink
    else -> WardIconColors.blue
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null,
                      trailing: String? = null, enabled: Boolean = true,
                      showChevron: Boolean = true,
                      iconColor: Color = WardIconColors.blue, onClick: () -> Unit) {
    PanelListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (subtitle == null) null else ({ Text(subtitle) }),
        leadingContent = { SettingsIconTile(icon, iconColor) },
        trailingContent = if (trailing != null || showChevron) ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge)
                if (showChevron) Icon(Icons.Filled.ChevronRight, null)
            }
        }) else null,
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(vm: AppViewModel, key: String, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>,
                 onBack: () -> Unit, onNavigate: (String) -> Unit, onAddAccount: () -> Unit = {}) {
    BackHandler { onBack() }
    if (key.startsWith("account:")) {
        AccountSettingsPage(vm, key.substringAfter(':').toLong(), accounts, states, onBack)
        return
    }
    if (key == "about") { AboutPage(onBack, onDonate = { onNavigate("donate") }); return }
    if (key == "donate") { DonatePage(onBack); return }
    if (key == "security") { SecurityPage(vm, onBack); return }
    if (key == "xml") { XmlConsolePage(vm, onBack); return }
    if (key == "disco") { DiscoPage(vm, onBack); return }
    if (key == "storage") { StoragePage(vm, onBack); return }

    val state by vm.settings.state.collectAsState()
    val specs = SettingsSchema.specs.filter { it.page == key }
    val groups = specs.groupBy { it.group }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var repairing by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    val title = SettingsSchema.pages.firstOrNull { it.first == key }?.second ?: key

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { WardSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { WardBackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 34.dp)) {
            item { PageIntro(key) }
            for ((group, list) in groups) {
                item { SettingsSectionLabel(group) }
                item {
                    SettingsGroup {
                        list.forEachIndexed { index, spec ->
                            if (index > 0) SettingsDivider()
                            SettingRow(vm, spec, state[spec.key] ?: spec.default.toString())
                        }
                    }
                }
            }
            when (key) {
                "calls" -> {
                    item {
                        SettingsGroup {
                            ActionRow(Icons.Filled.PhoneInTalk, "Call diagnostics",
                                "Connection timeout, relay candidates and ICE errors are written to the XML console.",
                                iconColor = WardIconColors.cyan)
                            { onNavigate("xml") }
                        }
                    }
                }
                "general" -> {
                    item { SettingsSectionLabel("Battery") }
                    item { BatteryGroup() }
                    item { SettingsSectionLabel("Info") }
                    item {
                        SettingsGroup {
                            ActionRow(Icons.Filled.Info, "About Ward", "Version, licences and project links",
                                iconColor = WardIconColors.blue) { onNavigate("about") }
                            SettingsDivider()
                            ActionRow(Icons.Filled.Favorite, "Donate", "Support Ward’s development",
                                iconColor = WardIconColors.pink) { onNavigate("donate") }
                        }
                    }
                }
                "encryption" -> {
                    item { SettingsSectionLabel("Devices and keys") }
                    item {
                        SettingsGroup {
                            ActionRow(Icons.Filled.Devices, "Your devices",
                                "Fingerprints, verification and backups for each account",
                                iconColor = WardIconColors.teal) {
                                val id = accounts.firstOrNull()?.id
                                if (id == null) onAddAccount()
                                else if (accounts.size == 1) onNavigate("account:$id")
                                else onNavigate("accounts")
                            }
                            SettingsDivider()
                            ActionRow(Icons.Filled.Build, if (repairing) "Repairing OMEMO 2…" else "Repair OMEMO 2",
                                "Rebuild this device’s OMEMO 2 keys and publish a fresh bundle",
                                iconColor = WardIconColors.orange, enabled = !repairing) {
                                repairing = true
                                vm.repairOmemo2 { result ->
                                    repairing = false
                                    scope.launch { snackbar.showSnackbar(result, withDismissAction = true,
                                        duration = SnackbarDuration.Long) }
                                }
                            }
                            SettingsDivider()
                            ActionRow(Icons.Filled.DeleteSweep, if (cleaning) "Cleaning up…" else "Clean up old keys and devices",
                                "Fetch the server’s device list, republish this device and remove stale local keys",
                                iconColor = WardIconColors.red, enabled = !cleaning) {
                                cleaning = true
                                vm.cleanUpKeys { result ->
                                    cleaning = false
                                    scope.launch { snackbar.showSnackbar(result, withDismissAction = true,
                                        duration = SnackbarDuration.Long) }
                                }
                            }
                            if (repairing || cleaning) {
                                SettingsDivider()
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text(if (repairing) "Generating and publishing OMEMO 2 keys…" else "Comparing local keys with the server…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                "advanced" -> {
                    item { SettingsSectionLabel("Diagnostics") }
                    item {
                        SettingsGroup {
                            ActionRow(Icons.Filled.Code, "XML console", "Watch, search and send raw XMPP stanzas",
                                iconColor = WardIconColors.blue) { onNavigate("xml") }
                            SettingsDivider()
                            ActionRow(Icons.Filled.Dns, "Service discovery", "Browse the features a server publishes",
                                iconColor = WardIconColors.teal) { onNavigate("disco") }
                        }
                    }
                }
                "accounts" -> {
                    item {
                        SettingsGroup {
                            if (accounts.isEmpty()) {
                                ActionRow(Icons.Filled.PersonAdd, "Add account", "Sign in to an XMPP server",
                                    iconColor = WardIconColors.green) { onAddAccount() }
                            } else {
                                accounts.forEachIndexed { index, acc ->
                                    if (index > 0) SettingsDivider()
                                    val conn = states[acc.id]
                                    ActionRow(Icons.Filled.AccountCircle, acc.displayName,
                                        acc.jid + " · " + connectionLabel(conn),
                                        trailing = if (conn?.state == "online") "Online" else "Connect",
                                        showChevron = false) {
                                        onNavigate("account:${acc.id}")
                                    }
                                }
                                SettingsDivider()
                                ActionRow(Icons.Filled.PersonAdd, "Add account", "Sign in to another XMPP server",
                                    iconColor = WardIconColors.green) { onAddAccount() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageIntro(key: String) {
    val (icon, title, body) = when (key) {
        "calls" -> Triple(Icons.Filled.Call, "Audio and video calls",
            "Calls use Jingle signalling with WebRTC media. Relay servers can be discovered from your server or entered manually.")
        "encryption" -> Triple(Icons.Filled.Security, "Private by default",
            "OMEMO is the default. Legacy OMEMO keeps compatibility with other XMPP apps; OMEMO 2 can be selected per chat.")
        "privacy" -> Triple(Icons.Filled.VisibilityOff, "Control what leaves this device",
            "Strip metadata from photos and decide how Ward handles messages from people you have not added.")
        "notifications" -> Triple(Icons.Filled.Notifications, "Stay reachable, not interrupted",
            "Choose what a notification reveals, when it stays quiet, and which words should always get through.")
        "appearance" -> Triple(Icons.Filled.Palette, "Make Ward yours",
            "Every colour role follows the accent you pick, including cards, sheets, sliders and dialogs.")
        "chats" -> Triple(Icons.Filled.Chat, "Conversations",
            "Writing behaviour, reactions and the receipts and markers you share with other people.")
        "attachments" -> Triple(Icons.Filled.AttachFile, "Photos, videos and files",
            "Reduce upload sizes without giving up control over what downloads automatically.")
        "general" -> Triple(Icons.Filled.Tune, "Everyday behaviour",
            "Keep Ward connected in the background and choose how it starts after a reboot.")
        "storage" -> Triple(Icons.Filled.Storage, "Space on this phone",
            "See what downloaded media and thumbnails are using, and clear them safely.")
        "advanced" -> Triple(Icons.Filled.Tune, "For troubleshooting",
            "Connection timings and the raw protocol tools used to diagnose a server.")
        "accounts" -> Triple(Icons.Filled.People, "Accounts on this device",
            "Each account has its own profile, encryption identity and notification behaviour.")
        "security" -> Triple(Icons.Filled.Lock, "Lock Ward",
            "A PIN, password or pattern keeps local messages and encryption keys private. You can also block screenshots and the recent-apps preview.")
        else -> Triple(Icons.Filled.Settings, "Preferences", "")
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        SettingsIconTile(icon, pageIconColor(key), 44)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun BatteryGroup() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val ignoring = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    ActionRow(Icons.Filled.BatterySaver, "Battery optimisation",
        if (ignoring) "Ward is exempt; Android will not stop it in the background."
        else "Android may pause Ward in the background. Allow it to run without restrictions.",
        trailing = if (ignoring) "Exempt" else "Optimised", showChevron = false,
        iconColor = WardIconColors.green) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }
    }
}

@Composable
fun SettingRow(vm: AppViewModel, spec: SettingSpec, value: String) {
    when (spec.type) {
        SettingType.BOOL -> PanelListItem(
            headlineContent = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = if (spec.subtitle.isBlank()) null else ({ Text(spec.subtitle) }),
            trailingContent = {
                Switch(checked = value.toBoolean(), onCheckedChange = { vm.settings.set(spec.key, it) })
            })
        SettingType.CHOICE -> {
            var open by remember { mutableStateOf(false) }
            val label = spec.choices.firstOrNull { it.value.toString() == value }?.label ?: value
            PanelListItem(
                headlineContent = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                supportingContent = if (spec.subtitle.isBlank()) null else ({ Text(spec.subtitle) }),
                trailingContent = {
                    Box {
                        TextButton(onClick = { open = true }) {
                            Text(label)
                            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for (ch in spec.choices) DropdownMenuItem(
                                text = { Text(ch.label) },
                                trailingIcon = if (ch.value.toString() == value) ({
                                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }) else null,
                                onClick = { vm.settings.set(spec.key, ch.value); open = false })
                        }
                    }
                })
        }
        SettingType.INT -> {
            if ((spec.max ?: 100) - (spec.min ?: 0) > 1000) {
                var dialog by remember { mutableStateOf(false) }
                PanelListItem(headlineContent = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (spec.subtitle.isBlank()) "$value${spec.unit}" else "${spec.subtitle} · $value${spec.unit}") },
                    trailingContent = { Icon(Icons.Filled.Edit, null) },
                    modifier = Modifier.clickable { dialog = true })
                if (dialog) NumberDialog(spec, value, onDismiss = { dialog = false }) {
                    vm.settings.set(spec.key, it); dialog = false
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("$value${spec.unit}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Slider(value = value.toFloatOrNull() ?: 0f,
                        onValueChange = { vm.settings.set(spec.key, it.toInt()) },
                        valueRange = (spec.min ?: 0).toFloat()..(spec.max ?: 100).toFloat(),
                        steps = (((spec.max ?: 100) - (spec.min ?: 0)) / spec.step.coerceAtLeast(1) - 1).coerceAtLeast(0))
                    if (spec.subtitle.isNotBlank()) Text(spec.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SettingType.TEXT, SettingType.LIST, SettingType.TIME_RANGE -> {
            var dialog by remember { mutableStateOf(false) }
            PanelListItem(
                headlineContent = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(if (value.isBlank()) (spec.subtitle.ifBlank { "Default" }) else value.take(90),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { dialog = true })
            if (dialog) {
                TextValueDialog(spec, value, onDismiss = { dialog = false }) {
                    vm.settings.set(spec.key, it); dialog = false
                }
            }
        }
    }
}

@Composable
private fun TextValueDialog(spec: SettingSpec, value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf("") }
    val isPassword = spec.key.contains("password") || spec.key.contains("secret")
    val isList = spec.type == SettingType.LIST
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title) },
        text = {
            Column {
                if (spec.subtitle.isNotBlank()) {
                    Text(spec.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = "" },
                    minLines = if (isList) 5 else 1,
                    maxLines = if (isList) 10 else 5,
                    label = { Text(if (isList) "One per line" else "Value") },
                    placeholder = if (spec.type == SettingType.TIME_RANGE) ({ Text("22:00-07:00") }) else null,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password)
                        else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error.isNotBlank(),
                    supportingText = if (error.isNotBlank()) ({ Text(error) }) else null
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (spec.type == SettingType.TIME_RANGE && text.isNotBlank() && !Regex("^\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}$").matches(text.trim())) {
                    error = "Use a range like 22:00-07:00"
                } else {
                    onSave(text.trim())
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberDialog(spec: SettingSpec, value: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(spec.title) },
        text = {
            Column {
                if (spec.subtitle.isNotBlank()) Text(spec.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }; error = "" },
                    label = { Text("Value${if (spec.unit.isNotBlank()) " in ${spec.unit}" else ""}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error.isNotBlank(),
                    supportingText = if (error.isNotBlank()) ({ Text(error) }) else null)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.toIntOrNull()
                if (n == null || n < (spec.min ?: 0) || n > (spec.max ?: Int.MAX_VALUE)) {
                    error = "Enter a number between ${spec.min} and ${spec.max}"
                } else onSave(n)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsPage(vm: AppViewModel, id: Long, accounts: List<AccountEntity>, states: Map<Long, ConnectionState>,
                        onBack: () -> Unit) {
    val acc = accounts.firstOrNull { it.id == id }
    var nick by remember { mutableStateOf(acc?.nickname ?: "") }
    var password by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    var pgpFingerprint by remember { mutableStateOf("") }
    var pgpBusy by remember { mutableStateOf(false) }
    var savingName by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    if (acc == null) { onBack(); return }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        org.ward.android.util.AppLockStore.endExternalActivity()
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val file = File(context.filesDir, "profile-${acc.id}-${System.currentTimeMillis()}.jpg")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val current = vm.core.account(acc.id) ?: acc
                    vm.core.updateAccount(current.copy(avatar = Uri.fromFile(file).toString()))
                    vm.core.setAccountOption(current, "avatar_dirty", "true")
                    vm.core.session(acc.id)?.publishAvatar(bytes, mime)
                }
                val online = vm.core.isOnline(acc.id)
                snackbar.showSnackbar(result.fold({
                    if (online) "Profile photo saved and published" else "Profile photo saved; it will publish when connected"
                }, {
                    "Could not save the photo: ${it.message ?: "unknown error"}"
                }))
            }
        }
    }
    LaunchedEffect(acc.id) { pgpFingerprint = vm.core.accountOption(acc, "pgp_key") }
    val state = states[acc.id]

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { WardSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text(acc.displayName, style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { WardBackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background))
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 34.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(acc.displayName, 72, acc.color, photo = acc.avatar)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(acc.jid, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(connectionLabel(state), style = MaterialTheme.typography.bodySmall,
                            color = if (state?.state == "online") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SettingsSectionLabel("Connection") }
            item {
                SettingsGroup {
                    PanelListItem(
                        headlineContent = { Text(if (state?.state == "online") "Connected" else "Not connected",
                            style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(state?.error?.takeIf { it.isNotBlank() } ?: if (state?.state == "online")
                                "Messages and calls are arriving on this device." else "Ward reconnects automatically when it can.")
                        },
                        leadingContent = { Icon(if (state?.state == "online") Icons.Filled.CloudDone else Icons.Filled.CloudOff, null) }
                    )
                    SettingsDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state?.state == "online") {
                            OutlinedButton(onClick = {
                                vm.disconnect(acc)
                                scope.launch { snackbar.showSnackbar("Disconnected ${acc.displayName}") }
                            }) { Text("Disconnect") }
                        } else {
                            Button(onClick = {
                                vm.connect(acc)
                                scope.launch { snackbar.showSnackbar("Connecting ${acc.displayName}…") }
                            }) { Text("Connect") }
                        }
                        OutlinedButton(onClick = { qrOpen = true }) {
                            Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share address")
                        }
                    }
                }
            }

            item { SettingsSectionLabel("Profile") }
            item {
                SettingsGroup {
                    PanelListItem(
                        headlineContent = { Text("Profile photo", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text("Published with your XMPP profile so contacts see it") },
                        leadingContent = { Avatar(acc.displayName, 40, acc.color, photo = acc.avatar) },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                org.ward.android.util.AppLockStore.beginExternalActivity()
                                pickPhoto.launch("image/*")
                            }) { Text(if (acc.avatar.isBlank()) "Choose" else "Change") }
                        })
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(nick, { nick = it }, label = { Text("Nickname") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        Button(enabled = !savingName, onClick = {
                            savingName = true
                            scope.launch {
                                runCatching {
                                    vm.core.updateAccount(acc.copy(nickname = nick))
                                    vm.core.session(acc.id)?.publishNick(nick)
                                }
                                savingName = false
                                snackbar.showSnackbar(if (nick.isBlank()) "Nickname cleared" else "Nickname saved")
                            }
                        }) { Text(if (savingName) "Saving…" else "Save name") }
                    }
                }
            }

            item { SettingsSectionLabel("Security") }
            item {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(password, { password = it }, label = { Text("New password") },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                            singleLine = true, supportingText = { Text("At least six characters. Changes the password on the server.") })
                        Spacer(Modifier.height(10.dp))
                        Button(enabled = password.length >= 6 && !changingPassword, onClick = {
                            changingPassword = true
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val conn = vm.core.session(acc.id)?.connection ?: error("Connect this account first")
                                        org.jivesoftware.smackx.iqregister.AccountManager.getInstance(conn).changePassword(password)
                                    }
                                    vm.core.updateAccount(acc.copy(encryptedPassword = SecretStore.encrypt(password)))
                                }
                                changingPassword = false
                                if (result.isSuccess) password = ""
                                snackbar.showSnackbar(result.fold({ "Password changed" }, {
                                    "Could not change the password: ${it.message ?: "unknown error"}"
                                }))
                            }
                        }) { Text(if (changingPassword) "Changing…" else "Change password") }
                    }
                    SettingsDivider()
                    ActionRow(Icons.Filled.Devices, "Devices and keys",
                        "Fingerprints, trust and encrypted backups for your own OMEMO devices") { showDevices = true }
                }
            }

            item { SettingsSectionLabel("OpenPGP") }
            item {
                SettingsGroup {
                    ActionRow(Icons.Filled.VpnKey, if (pgpFingerprint.isBlank()) "Generate OpenPGP key" else "OpenPGP key",
                        if (pgpFingerprint.isBlank()) "No key yet; one is generated the first time you use OpenPGP"
                        else formatFingerprint(pgpFingerprint),
                        trailing = if (pgpBusy) "Working…" else if (pgpFingerprint.isBlank()) "Generate" else "Republish",
                        showChevron = false, enabled = !pgpBusy) {
                        pgpBusy = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val secret = org.ward.android.xmpp.OpenPgp.ensureKey(vm.core, acc)
                                    val fp = org.ward.android.xmpp.OpenPgp.fingerprint(secret)
                                    vm.core.setAccountOption(acc, "pgp_key", fp)
                                    vm.core.dao.insertPgpKey(PgpKeyEntity(acc.id, acc.jid, fp,
                                        org.ward.android.xmpp.OpenPgp.publicArmor(secret), name = acc.displayName, trust = "verified"))
                                    fp
                                }
                            }
                            pgpBusy = false
                            result.onSuccess {
                                pgpFingerprint = it
                                vm.core.session(acc.id)?.ensurePgp()
                            }
                            snackbar.showSnackbar(result.fold({ "OpenPGP key published" }, {
                                "Could not create the key: ${it.message ?: "unknown error"}"
                            }))
                        }
                    }
                }
            }

            item { SettingsSectionLabel("Remove") }
            item {
                SettingsGroup {
                    PanelListItem(headlineContent = { Text("Remove account from Ward…",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text("The account stays on the server; local history and keys are deleted.") },
                        leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { confirmRemove = true })
                }
            }
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
private fun StoragePage(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf(0L) }
    var fileCount by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) {
        refreshing = true
        val (bytes, count) = withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, "files")
            val files = if (root.exists()) root.walkTopDown().filter { it.isFile }.toList() else emptyList()
            files.sumOf { it.length() } to files.size
        }
        cacheBytes = bytes
        fileCount = count
        refreshing = false
    }
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes bytes"
    }
    BackHandler { onBack() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { WardSnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Storage", style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = { WardBackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            PageIntro("storage")
            SettingsSectionLabel("Cache")
            SettingsGroup {
                PanelListItem(headlineContent = { Text("Downloaded media and thumbnails", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (refreshing) "Calculating…" else "$fileCount files · ${sizeLabel(cacheBytes)}") },
                    leadingContent = { SettingsIconTile(Icons.Filled.Folder, WardIconColors.yellow) },
                    trailingContent = if (refreshing) ({ CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }) else null)
                SettingsDivider()
                PanelListItem(headlineContent = { Text("Clear cached files", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Downloads and thumbnails will be fetched again when you open a chat.") },
                    leadingContent = { SettingsIconTile(Icons.Filled.CleaningServices, WardIconColors.pink) },
                    modifier = Modifier.clickable(enabled = cacheBytes > 0 || !refreshing) {
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) {
                                val before = cacheBytes
                                File(context.cacheDir, "files").listFiles()?.forEach { it.deleteRecursively() }
                                before
                            }
                            refreshKey++
                            snackbar.showSnackbar(if (freed > 0) "Cleared ${sizeLabel(freed)}" else "Cache is already empty")
                        }
                    })
            }
            SettingsSectionLabel("Diagnostics")
            SettingsGroup {
                PanelListItem(headlineContent = { Text("Clear the XML console", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Diagnostic stanzas kept in memory for this session") },
                    leadingContent = { SettingsIconTile(Icons.Filled.Code, WardIconColors.cyan) },
                    modifier = Modifier.clickable {
                        vm.core.clearXml()
                        scope.launch { snackbar.showSnackbar("XML console cleared") }
                    })
                SettingsDivider()
                PanelListItem(headlineContent = { Text("Refresh size", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Recalculate the cache after downloading files") },
                    leadingContent = { SettingsIconTile(Icons.Filled.Refresh, WardIconColors.grey) },
                    modifier = Modifier.clickable { refreshKey++ })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonatePage(onBack: () -> Unit) {
    val context = LocalContext.current
    fun copy(label: String, value: String) {
        context.copyText(value)
        android.widget.Toast.makeText(context, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Donate", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
            WardBackButton(onBack)
        }, colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Filled.Favorite, null, Modifier.padding(10.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Keep Ward independent", style = MaterialTheme.typography.titleMedium)
                    Text("Ward is free and open source. Donations pay for development and the servers behind it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsSectionLabel("Crypto")
            SettingsGroup {
                DonationRow(Icons.Filled.CurrencyBitcoin, "Bitcoin (BTC)", "bc1qy2gtdhnfxp9dcs6v9jda748npmsjx3jgwp99mx", WardIconColors.orange) { copy("Bitcoin address", it) }
                SettingsDivider()
                DonationRow(Icons.Filled.CurrencyExchange, "Monero (XMR)", "82xtMVSmesuLjPtgHfBCEhM5Fpqh1SLLNf9pzHRRNPqQZsvrnmoM1ZGC7AiLyPfsufdyrMWHrWYV2hsC8jc5rEBVLMHWTLy", WardIconColors.orange) { copy("Monero address", it) }
            }
            SettingsSectionLabel("Other")
            SettingsGroup {
                DonationRow(Icons.Filled.Payments, "Cash App", "\$SmileyMcSmiles", WardIconColors.green) { copy("Cash App tag", it) }
            }
            Text("Tap an address to copy it to the clipboard.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun DonationRow(icon: ImageVector, label: String, value: String, iconColor: Color,
                        onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsIconTile(icon, iconColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(value, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onCopy(value) }) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(onBack: () -> Unit, onDonate: () -> Unit = {}) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("About Ward", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
            WardBackButton(onBack)
        }, colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Avatar("Ward", 96, "#3584e4")
            Spacer(Modifier.height(16.dp))
            Text("Ward", style = MaterialTheme.typography.headlineMedium)
            Text("${BuildConfig.VERSION_NAME} for Android", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Text("Private messaging on the open XMPP network, encrypted by default with OMEMO. Markdown messages, groups, calls, files, and a native server administration console for Prosody and ejabberd.",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(22.dp))
            SettingsGroup {
                ActionRow(Icons.Filled.Public, "Project website", "sonder.guru",
                    iconColor = WardIconColors.blue) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sonder.guru"))) }
                }
                SettingsDivider()
                ActionRow(Icons.Filled.Code, "Source code", "github.com/Smiley-McSmiles/Ward-Android",
                    iconColor = WardIconColors.grey) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Smiley-McSmiles/Ward-Android"))) }
                }
                SettingsDivider()
                ActionRow(Icons.Filled.SystemUpdate, "Check for updates",
                    if (checking) "Checking GitHub…" else "Ward ${BuildConfig.VERSION_NAME} is installed",
                    iconColor = WardIconColors.green, showChevron = false) {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val info = withContext(Dispatchers.IO) { UpdateCheck.latest() }
                            checking = false
                            when {
                                info == null -> android.widget.Toast.makeText(context,
                                    "Could not reach GitHub", android.widget.Toast.LENGTH_SHORT).show()
                                UpdateCheck.isNewer(info.version, BuildConfig.VERSION_NAME) -> found = info
                                else -> android.widget.Toast.makeText(context,
                                    "Ward is up to date", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                SettingsDivider()
                ActionRow(Icons.Filled.Favorite, "Donate", "Support Ward’s development",
                    iconColor = WardIconColors.pink) { onDonate() }
            }
            Spacer(Modifier.height(18.dp))
            Text("MIT licensed. OMEMO uses the Signal protocol. Adapted from Ward for GTK by Smiley McSmiles.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp))
        }
    }
    found?.let { info -> UpdateAvailableDialog(info) { found = null } }
}
