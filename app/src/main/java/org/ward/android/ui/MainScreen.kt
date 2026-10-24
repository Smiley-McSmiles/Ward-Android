package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ward.android.data.*
import org.ward.android.ConnectionState
import org.ward.android.util.JidUtil
import org.ward.android.util.TimeUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, onAddAccount: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val chats by vm.chats.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val requests by vm.requests.collectAsState()
    val states by vm.states.collectAsState()
    val typing by vm.typing.collectAsState()
    val selected by vm.selectedChat.collectAsState()
    var screen by remember { mutableStateOf("chats") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var showArchived by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<String?>(null) }
    var addAccountScreen by remember { mutableStateOf(false) }
    var accountFilter by remember { mutableStateOf<Long?>(null) }
    var passwordFor by remember { mutableStateOf<AccountEntity?>(null) }
    LaunchedEffect(accounts) {
        if (accountFilter != null && accounts.none { it.id == accountFilter }) accountFilter = null
    }
    val currentChat by vm.selectedChatEntity.collectAsState()
    if (addAccountScreen) { WelcomeScreen(vm, onBack = { addAccountScreen = false }); return }
    val showChat = selected != null && currentChat != null
    val hasAdmin = accounts.any { it.isAdmin }
    BackHandler(enabled = screen != "chats" && !showChat) { screen = "chats" }
    BackHandler(enabled = searchMode) { searchMode = false; search = "" }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    AnimatedContent(targetState = screen,
        transitionSpec = {
            (slideInHorizontally(animationSpec = tween(200)) { it } + fadeIn(tween(140))) togetherWith
                (slideOutHorizontally(animationSpec = tween(150)) { -it / 4 } + fadeOut(tween(100)))
        }, label = "main") { current ->
        when (current) {
            "settings" -> SettingsTab(vm, accounts, states, PaddingValues(0.dp),
                onAddAccount = { addAccountScreen = true }, onBack = { screen = "chats"; settingsPage = null },
                initialPage = settingsPage)
            "server" -> ServerTab(vm, accounts, states, PaddingValues(0.dp), onBack = { screen = "chats" })
            else -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background), title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { accountMenu = true }) {
                                    val active = accounts.firstOrNull { it.id == accountFilter } ?: accounts.firstOrNull()
                                    Avatar(active?.displayName ?: "Ward", 30,
                                        active?.color ?: "",
                                        photo = active?.avatar ?: "")
                                }
                                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                    DropdownMenuItem(text = { Text("All accounts") },
                                        leadingIcon = { if (accountFilter == null) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                                        onClick = { accountFilter = null; accountMenu = false })
                                    for (acc in accounts) {
                                        DropdownMenuItem(text = {
                                            Column { Text(acc.displayName, fontWeight = FontWeight.Medium); Text(acc.jid, style = MaterialTheme.typography.bodySmall) }
                                        }, leadingIcon = {
                                            if (accountFilter == acc.id) Icon(Icons.Filled.Check, null)
                                            else Avatar(acc.displayName, 32, acc.color,
                                                show = states[acc.id]?.state?.let { if (it == "online") "available" else "offline" },
                                                photo = acc.avatar)
                                        },
                                            onClick = { accountFilter = acc.id; accountMenu = false })
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Account settings") },
                                        leadingIcon = { Icon(Icons.Filled.ManageAccounts, null) },
                                        onClick = {
                                            val id = accountFilter ?: accounts.firstOrNull()?.id
                                            accountMenu = false
                                            if (id != null) { settingsPage = "account:$id"; screen = "settings" }
                                        })
                                    DropdownMenuItem(text = { Text("Manage accounts") },
                                        leadingIcon = { Icon(Icons.Filled.People, null) },
                                        onClick = { accountMenu = false; settingsPage = "accounts"; screen = "settings" })
                                    DropdownMenuItem(text = { Text("Add account") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                                        onClick = { accountMenu = false; addAccountScreen = true })
                                    HorizontalDivider()
                                    for ((show, label) in listOf("available" to "Available", "away" to "Away", "dnd" to "Busy", "xa" to "Away for a while", "offline" to "Offline")) {
                                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { PresenceDot(show) },
                                            onClick = { accountMenu = false; vm.setPresence(show) })
                                    }
                                }
                            }
                            Text("Ward", fontWeight = FontWeight.Bold)
                        }
                    }, actions = {
                        IconButton(onClick = { searchMode = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = { overflowMenu = false; settingsPage = null; screen = "settings" })
                                DropdownMenuItem(text = { Text("New group") }, leadingIcon = { Icon(Icons.Filled.GroupAdd, null) },
                                    onClick = { overflowMenu = false; dialog = "group" })
                                DropdownMenuItem(text = { Text("Join group") }, leadingIcon = { Icon(Icons.Filled.Login, null) },
                                    onClick = { overflowMenu = false; dialog = "join" })
                                if (hasAdmin) DropdownMenuItem(text = { Text("Server administration") },
                                    leadingIcon = { Icon(Icons.Filled.Dns, null) },
                                    onClick = { overflowMenu = false; screen = "server" })
                            }
                        }
                    })
                }
            ) { pad ->
                Box(Modifier.fillMaxSize()) {
                    ChatsTab(vm, chats, requests, typing, states, search, { search = it }, filter, { filter = it }, showArchived, { showArchived = it }, accountFilter, pad,
                        searchMode, onCloseSearch = { searchMode = false; search = "" }, onNewChat = { dialog = "new" })
                    if (hasAdmin) {
                        SmallFloatingActionButton(onClick = { screen = "server" }, shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(18.dp)) {
                            Icon(Icons.Filled.Dns, "Server administration")
                        }
                    }
                    FloatingActionButton(onClick = { addSheet = true }, shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp)) {
                        Icon(Icons.Filled.Add, "New message or contact")
                    }
                }
            }
        }
    }
    if (passwordFor != null) {
        var pw by remember(passwordFor) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordFor = null }, title = { Text("Password") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password for ${passwordFor!!.jid}") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val a = passwordFor!!; passwordFor = null
                vm.core.scope.launch { vm.core.updateAccount(a.copy(encryptedPassword = org.ward.android.util.SecretStore.encrypt(pw))) }
                vm.connect(a, pw)
            }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Cancel") } })
    }
    AnimatedVisibility(visible = showChat,
        enter = slideInHorizontally(animationSpec = tween(170)) { it / 4 } + fadeIn(tween(120)),
        exit = ExitTransition.None, label = "chat") {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { ChatScreen(vm) }
    }
    }
    if (!showChat) when (dialog) {
        "new" -> NewChatDialog(vm, accountFilter, onDismiss = { dialog = null })
        "contact" -> AddContactDialog(vm, accountFilter, onDismiss = { dialog = null })
        "group" -> NewGroupDialog(vm, accountFilter, onDismiss = { dialog = null })
        "join" -> JoinGroupDialog(vm, accountFilter, onDismiss = { dialog = null })
    }
    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("Start something", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("Choose what you want to create.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                ListItem(
                    headlineContent = { Text("New message") },
                    supportingContent = { Text("Start a chat with an XMPP address") },
                    leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                    modifier = Modifier.clickable {
                        addSheet = false
                        dialog = "new"
                    })
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text("Add contact") },
                    supportingContent = { Text("Send someone a contact request") },
                    leadingContent = { Icon(Icons.Outlined.PersonAdd, null) },
                    modifier = Modifier.clickable {
                        addSheet = false
                        dialog = "contact"
                    })
            }
        }
    }
}

@Composable
fun ChatsTab(vm: AppViewModel, chats: List<ChatEntity>, requests: List<RequestEntity>, typing: Map<Long, String>,
             states: Map<Long, ConnectionState>, search: String, setSearch: (String) -> Unit,
             filter: String, setFilter: (String) -> Unit, archived: Boolean, setArchived: (Boolean) -> Unit,
             accountFilter: Long?, pad: PaddingValues, searchMode: Boolean, onCloseSearch: () -> Unit,
             onNewChat: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var messageResults by remember { mutableStateOf<List<org.ward.android.data.MessageEntity>>(emptyList()) }
    LaunchedEffect(search) {
        if (search.length >= 2) { kotlinx.coroutines.delay(250); messageResults = vm.core.dao.searchAll(search) }
        else messageResults = emptyList()
    }
    val visible = chats.filter { c ->
        c.open && !c.isRequest && c.archived == archived &&
            (accountFilter == null || c.accountId == accountFilter) &&
            (filter == "all" || (filter == "unread" && c.unread > 0) || (filter == "people" && !c.isGroup) || (filter == "groups" && c.isGroup)) &&
            (search.isBlank() || c.displayName.contains(search, true) || c.jid.contains(search, true))
    }
    val offlineAccount = accounts.firstOrNull {
        (accountFilter == null || it.id == accountFilter) && states[it.id]?.state != "online"
    }
    Column(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
        AnimatedVisibility(visible = searchMode, enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                OutlinedTextField(search, setSearch, placeholder = { Text("Search chats") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { IconButton(onClick = onCloseSearch) { Icon(Icons.Filled.Close, "Close search") } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((k, l) in listOf("all" to "All", "unread" to "Unread", "people" to "People", "groups" to "Groups")) {
                FilterChip(selected = filter == k, onClick = { setFilter(k) }, label = { Text(l) })
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(checked = archived, onCheckedChange = setArchived) { Icon(Icons.Filled.Folder, "Archived") }
        }
        val shownRequests = requests.filter { accountFilter == null || it.accountId == accountFilter }
        if (shownRequests.isNotEmpty()) {
            Card(onClick = { vm.selectChat(shownRequests.first().chatId) }, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MarkEmailUnread, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("${shownRequests.size} message request${if (shownRequests.size == 1) "" else "s"}", modifier = Modifier.weight(1f))
                    Text("Review", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (offlineAccount != null && states[offlineAccount.id]?.state != "connecting") {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(states[offlineAccount.id]?.error?.ifBlank { "You are offline. Messages you write are sent when you are back online." }
                        ?: "You are offline. Messages you write are sent when you are back online.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (states[offlineAccount.id]?.state == "error" || states[offlineAccount.id]?.state == "offline")
                        TextButton(onClick = { vm.connect(offlineAccount) }) { Text("Connect") }
                }
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(if (archived) "No archived chats" else "No chats", style = MaterialTheme.typography.titleMedium)
                    Text("Start a chat or join a group with the + button", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 92.dp)) {
                if (messageResults.isNotEmpty()) {
                    item { Text("Messages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                    items(messageResults.take(30), key = { "m" + it.id }) { m ->
                        val chat = chats.firstOrNull { it.id == m.chatId }
                        ListItem(headlineContent = { Text(chat?.displayName ?: m.senderName, maxLines = 1) },
                            supportingContent = { Text(m.body.ifBlank { "[attachment]" }, maxLines = 2) },
                            leadingContent = { Icon(Icons.Filled.ChatBubble, null) },
                            modifier = Modifier.clickable { vm.selectChat(m.chatId) })
                    }
                    item { HorizontalDivider() }
                }
                items(visible, key = { it.id }) { chat ->
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                vm.setArchived(chat, !archived)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.CenterEnd) {
                                Icon(if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                    if (archived) "Unarchive" else "Archive", Modifier.padding(end = 24.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    ) {
                        ChatRow(chat, typing[chat.id] ?: "", onClick = { vm.selectChat(chat.id) },
                            modifier = Modifier)
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsTab(vm: AppViewModel, contacts: List<ContactEntity>, pad: PaddingValues, onAdd: () -> Unit,
                onNewGroup: () -> Unit, onJoin: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val shown = contacts.filter { it.inRoster && (search.isBlank() || it.name.contains(search, true) || it.jid.contains(search, true)) }
        .sortedWith(compareBy({ it.presence == "offline" }, { it.name.lowercase() }))
    Column(Modifier.fillMaxSize().padding(pad)) {
        OutlinedTextField(search, { search = it }, placeholder = { Text("Search contacts") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true, shape = CircleShape)
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onAdd, label = { Text("Add contact") }, leadingIcon = { Icon(Icons.Filled.PersonAdd, null) })
            AssistChip(onClick = onNewGroup, label = { Text("New group") }, leadingIcon = { Icon(Icons.Filled.GroupAdd, null) })
            AssistChip(onClick = onJoin, label = { Text("Join") }, leadingIcon = { Icon(Icons.Filled.Login, null) })
        }
        if (shown.isEmpty()) EmptyState(Icons.Outlined.People, "No contacts", "Add someone with their XMPP address") else
            LazyColumn { items(shown, key = { it.accountId.toString() + it.jid }) { c ->
                ContactRow(c) { vm.openChat(c.accountId, c.jid) }
            } }
    }
}

private val commonXmppServers = listOf(
    "jabber.org",
    "xmpp.jp",
    "conversation.im",
    "disroot.org",
    "chinwag.im",
    "jabber.de",
)

@Composable
private fun JidSuggestions(value: String, accountId: Long, accounts: List<AccountEntity>, onPick: (String) -> Unit) {
    val at = value.indexOf('@')
    val local = (if (at >= 0) value.substring(0, at) else value).trim()
    if (local.isBlank() || local.any { it.isWhitespace() }) return
    val domainPart = if (at >= 0) value.substring(at + 1).trim() else ""
    val domains = linkedSetOf<String>()
    accounts.firstOrNull { it.id == accountId }?.domain?.takeIf { it.isNotBlank() }?.let { domains.add(it) }
    accounts.map { it.domain }.filter { it.isNotBlank() }.forEach { domains.add(it) }
    domains.addAll(commonXmppServers)
    val suggestions = domains
        .filter { domainPart.isBlank() || it.startsWith(domainPart, ignoreCase = true) }
        .map { "$local@$it" }
        .filter { !it.equals(value.trim(), ignoreCase = true) }
        .take(6)
    if (suggestions.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { suggestion ->
            SuggestionChip(onClick = { onPick(suggestion) }, label = { Text(suggestion) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatDialog(vm: AppViewModel, initialAccountId: Long? = null, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    var jid by remember { mutableStateOf("") }
    var accountId by remember(initialAccountId, accounts.map { it.id }) {
        mutableStateOf(initialAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New chat") }, text = {
        Column {
            if (accounts.size > 1) {
                Text("Account", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id },
                        label = { Text(a.displayName) }) }
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(jid, { jid = it }, label = { Text("Address, like alex@example.org") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            JidSuggestions(jid, accountId, accounts) { jid = it }
            Spacer(Modifier.height(10.dp))
            Text("Contacts", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(contacts.filter { it.inRoster && (accountId == 0L || it.accountId == accountId) }.take(20)) { c ->
                    ListItem(headlineContent = { Text(c.name) }, supportingContent = { Text(c.jid) },
                        leadingContent = { Avatar(c.name, 34, photo = c.avatar, show = c.presence) },
                        modifier = Modifier.clickable { jid = c.jid })
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val j = JidUtil.bare(jid)
            if (JidUtil.valid(j) && accountId != 0L) { vm.openChat(accountId, j); onDismiss() }
        }) { Text("Chat") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun AddContactDialog(vm: AppViewModel, initialAccountId: Long? = null, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var jid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var accountId by remember(initialAccountId, accounts.map { it.id }) {
        mutableStateOf(initialAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add contact") }, text = {
        Column {
            if (accounts.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.displayName) }) }
            }
            OutlinedTextField(jid, { jid = it }, label = { Text("Address") }, singleLine = true)
            JidSuggestions(jid, accountId, accounts) { jid = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
            Text("They get a request to share their status with you", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val j = JidUtil.bare(jid)
            if (JidUtil.valid(j) && accountId != 0L) { vm.addContactAndOpen(accountId, j, name); onDismiss() }
        }) { Text("Add") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun NewGroupDialog(vm: AppViewModel, initialAccountId: Long? = null, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    var invitees by remember { mutableStateOf<Set<String>>(emptySet()) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(true) }
    var accountId by remember(initialAccountId, accounts.map { it.id }) {
        mutableStateOf(initialAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    var address by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New group") }, text = {
        Column {
            if (accounts.size > 1) {
                Text("Account", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.displayName) }) }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(private, { private = it }); Text("Private group") }
            Text(if (private) "Only invited members; messages can be end-to-end encrypted" else "Anyone can find and join; not encrypted",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (private) {
                Spacer(Modifier.height(8.dp))
                Text("Invite members", style = MaterialTheme.typography.labelMedium)
                val choices = contacts.filter {
                    it.inRoster && it.accountId == accountId && it.jid != accounts.firstOrNull { a -> a.id == accountId }?.jid
                }
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(choices, key = { it.jid }) { c ->
                        Row(Modifier.fillMaxWidth().clickable {
                            invitees = if (c.jid in invitees) invitees - c.jid else invitees + c.jid
                        }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.jid in invitees, onCheckedChange = {
                                invitees = if (it) invitees + c.jid else invitees - c.jid
                            })
                            Column { Text(c.name, style = MaterialTheme.typography.bodyMedium); Text(c.jid, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(address, { address = it }, label = { Text("Address (optional)") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (name.isBlank() || accountId == 0L) return@TextButton
            val room = address.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + "@conference." + accounts.first { it.id == accountId }.domain }
            vm.createGroup(accountId, room, name, private, description, if (private) invitees.toList() else emptyList())
            onDismiss()
        }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun JoinGroupDialog(vm: AppViewModel, initialAccountId: Long? = null, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var address by remember { mutableStateOf("") }
    var accountId by remember(initialAccountId, accounts.map { it.id }) {
        mutableStateOf(initialAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Join group or channel") }, text = {
        Column {
            if (accounts.size > 1) {
                Text("Account", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.displayName) }) }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true)
            Text("Enter the group's address, like team@groups.example.org", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val j = JidUtil.bare(address)
            if (JidUtil.valid(j) && accountId != 0L) { vm.openChat(accountId, j, "groupchat"); onDismiss() }
        }) { Text("Join") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
