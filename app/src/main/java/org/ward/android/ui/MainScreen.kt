package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import org.ward.android.xmpp.contactJidFromQr

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
    var skipNextScreenTransition by remember { mutableStateOf(false) }
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
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var resyncingHistory by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedChatIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    val selectedChats = chats.filter { it.id in selectedChatIds }
    fun clearChatSelection() {
        selectionMode = false
        selectedChatIds = emptySet()
    }
    fun startChatSelection(id: Long) {
        searchMode = false
        search = ""
        selectedChatIds = setOf(id)
        selectionMode = true
    }
    fun toggleChatSelection(id: Long) {
        val next = if (id in selectedChatIds) selectedChatIds - id else selectedChatIds + id
        selectedChatIds = next
        selectionMode = next.isNotEmpty()
    }
    LaunchedEffect(accounts) {
        if (accountFilter != null && accounts.none { it.id == accountFilter }) accountFilter = null
    }
    val currentChat by vm.selectedChatEntity.collectAsState()
    if (addAccountScreen) { WelcomeScreen(vm, onBack = { addAccountScreen = false }); return }
    val showChat = selected != null && currentChat != null
    val hasAdmin = accounts.any { it.isAdmin }
    BackHandler(enabled = screen != "chats" && !showChat) { screen = "chats" }
    BackHandler(enabled = searchMode) { searchMode = false; search = "" }
    BackHandler(enabled = selectionMode) { clearChatSelection() }
    LaunchedEffect(filter, showArchived, accountFilter) {
        if (selectionMode) clearChatSelection()
    }
    // The chat list stays composed underneath settings/server so a swipe can reveal it.
    val chatListContent: @Composable () -> Unit = {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { WardSnackbarHost(snackbar) },
            topBar = {
                if (selectionMode) {
                    SelectionTopBar(
                        count = selectedChatIds.size,
                        archivedView = showArchived,
                        onClose = { clearChatSelection() },
                        onArchive = {
                            val targets = selectedChats
                            val archive = !showArchived
                            vm.setArchivedAll(targets, archive) {
                                clearChatSelection()
                                uiScope.launch {
                                    snackbar.showSnackbar(
                                        (if (archive) "Archived " else "Unarchived ") + chatCount(targets.size))
                                }
                            }
                        },
                        onRead = {
                            val targets = selectedChats
                            vm.markReadAll(targets) {
                                clearChatSelection()
                                uiScope.launch { snackbar.showSnackbar("Marked ${chatCount(targets.size)} as read") }
                            }
                        },
                        onDelete = { confirmDeleteSelection = true }
                    )
                } else {
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
                    IconButton(
                        enabled = !resyncingHistory,
                        onClick = {
                            resyncingHistory = true
                            vm.resyncHistory(accountFilter) { result ->
                                resyncingHistory = false
                                uiScope.launch {
                                    snackbar.showSnackbar(result, withDismissAction = true,
                                        duration = SnackbarDuration.Long)
                                }
                            }
                        }
                    ) {
                        if (resyncingHistory) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, "Re-sync chat history from the server")
                        }
                    }
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
            }
        ) { pad ->
            Box(Modifier.fillMaxSize()) {
                ChatsTab(vm, chats, requests, typing, states, search, { search = it }, filter, { filter = it },
                    showArchived, { showArchived = it }, accountFilter, pad, searchMode,
                    onCloseSearch = { searchMode = false; search = "" }, onNewChat = { dialog = "new" },
                    selectionMode = selectionMode, selectedChatIds = selectedChatIds,
                    onToggleSelect = { toggleChatSelection(it) },
                    onLongSelect = { startChatSelection(it) })
                if (!selectionMode) {
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Always compose the chat list beneath overlays so settings swipes reveal real content.
    chatListContent()
    LaunchedEffect(screen) {
        if (skipNextScreenTransition) skipNextScreenTransition = false
    }
    AnimatedContent(targetState = screen,
        transitionSpec = {
            if (skipNextScreenTransition) {
                // The settings page has already been swiped fully off-screen; another entrance
                // animation would make the destination pop in twice.
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                // Pure horizontal push. The default AnimatedContent size transform makes the
                // incoming page grow from a corner while its measured size changes.
                (slideInHorizontally(animationSpec = tween(240)) { it } togetherWith
                    slideOutHorizontally(animationSpec = tween(240)) { -it / 3 }).using(null)
            }
        },
        label = "main") { current ->
        when (current) {
            "settings" -> SettingsTab(vm, accounts, states, PaddingValues(0.dp),
                onAddAccount = { addAccountScreen = true },
                onBack = { screen = "chats"; settingsPage = null },
                onSwipeBack = {
                    skipNextScreenTransition = true
                    screen = "chats"
                    settingsPage = null
                },
                initialPage = settingsPage)
            "server" -> ServerTab(vm, accounts, states, PaddingValues(0.dp),
                onBack = { screen = "chats" },
                onSwipeBack = {
                    skipNextScreenTransition = true
                    screen = "chats"
                })
            else -> Unit
        }
    }
    if (passwordFor != null) {
        var pw by remember(passwordFor) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordFor = null }, title = { Text("Password") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password for ${passwordFor!!.jid}") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val a = passwordFor!!; passwordFor = null
                org.ward.android.util.SecretStore.encryptOrNull(pw)?.let { stored ->
                    vm.core.scope.launch { vm.core.updateAccount(a.copy(encryptedPassword = stored)) }
                }
                vm.connect(a, pw)
            }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Cancel") } })
    }
    if (confirmDeleteSelection) {
        val count = selectedChatIds.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelection = false },
            title = { Text("Delete ${chatCount(count)}?") },
            text = { Text("This clears local history and removes the selected chats from the list. " +
                "They will reappear if someone sends a new message. Messages on the server are not deleted.") },
            confirmButton = {
                TextButton(enabled = count > 0, onClick = {
                    val targets = selectedChats
                    confirmDeleteSelection = false
                    vm.deleteChats(targets) {
                        clearChatSelection()
                        uiScope.launch { snackbar.showSnackbar("Deleted ${chatCount(targets.size)}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelection = false }) { Text("Cancel") } })
    }
    AnimatedVisibility(visible = showChat,
        enter = slideInHorizontally(animationSpec = tween(170)) { it / 4 } + fadeIn(tween(120)),
        exit = ExitTransition.None, label = "chat") {
        Box(Modifier.fillMaxSize()) { ChatScreen(vm) }
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
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AddAction(
                    icon = Icons.Outlined.PersonAdd,
                    label = "Add Contact",
                    modifier = Modifier.weight(1f)
                ) {
                    addSheet = false
                    dialog = "contact"
                }
                AddAction(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "New Message",
                    modifier = Modifier.weight(1f)
                ) {
                    addSheet = false
                    dialog = "new"
                }
            }
        }
    }
}

@Composable
private fun AddAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                      modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(34.dp),
        color = wardPanelColor(), tonalElevation = 0.dp,
        modifier = modifier.aspectRatio(1f)) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun ChatsTab(vm: AppViewModel, chats: List<ChatEntity>, requests: List<RequestEntity>, typing: Map<Long, String>,
             states: Map<Long, ConnectionState>, search: String, setSearch: (String) -> Unit,
             filter: String, setFilter: (String) -> Unit, archived: Boolean, setArchived: (Boolean) -> Unit,
             accountFilter: Long?, pad: PaddingValues, searchMode: Boolean, onCloseSearch: () -> Unit,
             onNewChat: () -> Unit, selectionMode: Boolean = false, selectedChatIds: Set<Long> = emptySet(),
             onToggleSelect: (Long) -> Unit = {}, onLongSelect: (Long) -> Unit = {}) {
    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val settings by vm.settings.state.collectAsState()
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
            Card(onClick = { vm.selectChat(shownRequests.first().chatId) }, shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = wardPanelColor()),
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
                        PanelListItem(headlineContent = { Text(chat?.displayName ?: m.senderName, maxLines = 1) },
                            supportingContent = { Text(m.body.ifBlank { "[attachment]" }, maxLines = 2) },
                            leadingContent = { Icon(Icons.Filled.ChatBubble, null) },
                            modifier = Modifier.clickable { vm.selectChat(m.chatId) })
                    }
                    item { HorizontalDivider() }
                }
                items(visible, key = { it.id }) { chat ->
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (selectionMode) false
                            else if (value == SwipeToDismissBoxValue.EndToStart) {
                                vm.setArchived(chat, !archived)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = !selectionMode,
                        gesturesEnabled = !selectionMode,
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
                        val contactPhoto = contacts.firstOrNull {
                            it.accountId == chat.accountId && it.jid == chat.jid
                        }?.avatar.orEmpty()
                        val avatarMode = settings["appearance.avatars"] ?: "always"
                        val isSelected = chat.id in selectedChatIds
                        ChatRow(chat, typing[chat.id] ?: "", onClick = {
                            if (selectionMode) onToggleSelect(chat.id) else vm.selectChat(chat.id)
                        },
                            modifier = Modifier,
                            photo = chat.avatar.ifBlank { contactPhoto },
                            showPreview = settings["appearance.list_previews"] != "false",
                            compact = settings["appearance.list_density"] == "compact",
                            showAvatar = avatarMode == "always" || (avatarMode == "groups" && chat.isGroup),
                            selectionMode = selectionMode,
                            selected = isSelected,
                            onLongClick = {
                                if (selectionMode) onToggleSelect(chat.id) else onLongSelect(chat.id)
                            })
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
    val valid = JidUtil.valid(JidUtil.bare(jid)) && accountId != 0L
    val shownContacts = contacts
        .filter { it.inRoster && (accountId == 0L || it.accountId == accountId) }
        .sortedWith(compareBy({ it.presence == "offline" }, { it.name.lowercase() }))

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("New chat", style = MaterialTheme.typography.headlineSmall)
            Text("Start a conversation with an XMPP address or a contact.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (accounts.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in accounts) {
                        FilterChip(selected = accountId == a.id, onClick = { accountId = a.id },
                            label = { Text(a.displayName) },
                            leadingIcon = if (accountId == a.id) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(jid, { jid = it }, placeholder = { Text("alex@example.org") },
                leadingIcon = { Icon(Icons.Filled.AlternateEmail, null) },
                trailingIcon = {
                    if (jid.isNotBlank()) IconButton(onClick = { jid = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth())
            if (jid.isNotBlank() && !valid) {
                Text("Enter a full address, like name@example.org",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            JidSuggestions(jid, accountId, accounts) { jid = it }
            Spacer(Modifier.height(16.dp))
            Text("Contacts", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            if (shownContacts.isEmpty()) {
                Text("No contacts yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shownContacts.take(50), key = { it.accountId.toString() + it.jid }) { c ->
                        Surface(onClick = { jid = c.jid }, shape = RoundedCornerShape(18.dp),
                            color = wardPanelColor(),
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.name, 38, photo = c.avatar, show = c.presence)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(c.jid, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                if (jid == c.jid) Icon(Icons.Filled.Check, "Selected",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(enabled = valid, onClick = {
                    vm.openChat(accountId, JidUtil.bare(jid))
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("Start chat") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactDialog(vm: AppViewModel, initialAccountId: Long? = null, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var jid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        org.ward.android.util.AppLockStore.endExternalActivity()
        val scanned = result.contents
        if (scanned != null) {
            val address = contactJidFromQr(scanned)
            if (address != null) { jid = address; error = "" }
            else error = "That QR code does not contain a valid XMPP address"
        }
    }
    var accountId by remember(initialAccountId, accounts.map { it.id }) {
        mutableStateOf(initialAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    val valid = JidUtil.valid(JidUtil.bare(jid)) && accountId != 0L
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Add contact", style = MaterialTheme.typography.headlineSmall)
            Text("Send someone a request to share their presence with you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (accounts.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in accounts) {
                        FilterChip(selected = accountId == a.id, onClick = { accountId = a.id },
                            label = { Text(a.displayName) },
                            leadingIcon = if (accountId == a.id) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(jid, { jid = it }, placeholder = { Text("alex@example.org") },
                leadingIcon = { Icon(Icons.Filled.AlternateEmail, null) },
                trailingIcon = {
                    if (jid.isNotBlank()) IconButton(onClick = { jid = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                org.ward.android.util.AppLockStore.beginExternalActivity()
                scan.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan a contact QR code")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                })
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code")
            }
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            } else if (jid.isNotBlank() && !valid) {
                Text("Enter a full address, like name@example.org",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            JidSuggestions(jid, accountId, accounts) { jid = it }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, placeholder = { Text("Name (optional)") },
                leadingIcon = { Icon(Icons.Filled.Person, null) },
                singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
            if (valid) {
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = wardPanelColor(),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name.ifBlank { JidUtil.nick(jid) }, 46)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name.ifBlank { JidUtil.nick(jid) }, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(JidUtil.bare(jid), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.PersonAdd, "Add contact", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(enabled = valid, onClick = {
                    vm.addContactAndOpen(accountId, JidUtil.bare(jid), name)
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("Add contact") }
            }
        }
    }
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
    var addressEdited by remember { mutableStateOf(false) }
    val accountDomain = accounts.firstOrNull { it.id == accountId }?.domain.orEmpty()
    val suggestedRoom = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    LaunchedEffect(name, accountDomain) {
        if (!addressEdited) {
            address = if (suggestedRoom.isBlank() || accountDomain.isBlank()) ""
            else "$suggestedRoom@conference.$accountDomain"
        }
    }
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
            OutlinedTextField(address, { address = it; addressEdited = it.isNotBlank() },
                label = { Text("Address (optional)") }, singleLine = true,
                supportingText = if (address.isNotBlank()) ({ Text("Room address") }) else null)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (name.isBlank() || accountId == 0L) return@TextButton
            val room = address.ifBlank {
                suggestedRoom + "@conference." + accounts.first { it.id == accountId }.domain
            }
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


private fun chatCount(count: Int): String = if (count == 1) "1 chat" else "$count chats"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    archivedView: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Exit selection") }
        },
        title = { Text("$count selected", style = MaterialTheme.typography.titleLarge) },
        actions = {
            IconButton(enabled = count > 0, onClick = onArchive) {
                Icon(if (archivedView) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    if (archivedView) "Unarchive selected chats" else "Archive selected chats")
            }
            IconButton(enabled = count > 0, onClick = onRead) {
                Icon(Icons.Outlined.Visibility, "Mark selected chats as read")
            }
            IconButton(enabled = count > 0, onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete selected chats", tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}
