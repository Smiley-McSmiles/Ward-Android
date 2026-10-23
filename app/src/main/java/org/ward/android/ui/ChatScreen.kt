package org.ward.android.ui

import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ward.android.data.*
import org.ward.android.parseReactions
import org.ward.android.util.Colors
import org.ward.android.util.TimeUtil
import org.ward.android.xmpp.OccupantInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    val chat by vm.selectedChatEntity.collectAsState()
    val messages by vm.messages.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val typing by vm.typing.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val states by vm.states.collectAsState()
    val appSettings by vm.settings.state.collectAsState()
    val c = chat ?: return
    val account = accounts.firstOrNull { it.id == c.accountId }
    val contact = contacts.firstOrNull { it.accountId == c.accountId && it.jid == c.jid }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchMode by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var messageDetails by remember { mutableStateOf<MessageEntity?>(null) }
    var members by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var devices by remember { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }
    var text by remember(c.id) { mutableStateOf(c.draft) }
    var initialized by remember(c.id) { mutableStateOf(false) }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.sendFile(uri)
    }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    fun startVoice() {
        try {
            val f = java.io.File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            voiceFile = f; recorder = r
        } catch (e: Exception) { recorder = null }
    }
    fun stopVoice() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
        voiceFile?.let { f -> if (f.length() > 1000) vm.sendFile(Uri.fromFile(f)) }
        voiceFile = null
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice()
    }
    var pendingVideoCall by remember { mutableStateOf(false) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.core.session(c.accountId)?.call?.start(c, pendingVideoCall)
    }
    fun startCall(video: Boolean) {
        pendingVideoCall = video
        val needed = if (video) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
            else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.core.session(c.accountId)?.call?.start(c, video)
        else callPermission.launch(missing.toTypedArray())
    }
    BackHandler {
        if (searchMode) searchMode = false else if (selectedMessage != null) selectedMessage = null else vm.selectChat(null)
    }
    LaunchedEffect(c.id) {
        vm.selectChat(c.id)
        if (c.isGroup && !c.joined) vm.core.session(c.accountId)?.joinRoom(c)
        if (vm.settings.bool("pgp.enabled")) {
            vm.core.session(c.accountId)?.ensurePgp()
            vm.core.session(c.accountId)?.refreshPgp(c.jid)
        }
        delay(100)
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
        vm.markRead()
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = lastVisible >= messages.size - 4
            val newest = messages.last()
            if (nearBottom || newest.direction == 1) {
                listState.animateScrollToItem(messages.lastIndex)
            }
            vm.markRead()
        }
    }
    LaunchedEffect(text) {
        if (!initialized) { initialized = true; return@LaunchedEffect }
        delay(500)
        vm.setDraft(text)
        if (text.isNotBlank()) {
            vm.typing("composing")
            delay(4000)
            vm.typing("paused")
        } else vm.typing("active")
    }
    val filtered = if (search.isBlank()) messages else messages.filter { it.body.contains(search, true) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            // Scaffold expects a single top-bar composable. Emitting the app bar and the search
            // field as siblings makes Scaffold place both at y=0, which hides the search field
            // under the status bar. A Column keeps them stacked and lets Scaffold measure both.
            Column(Modifier.fillMaxWidth()) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.selectChat(null) }) { Icon(Icons.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { details = true }) {
                        Avatar(c.displayName, 34, show = if (c.isGroup) null else contact?.presence)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                            val sub = when {
                                typing[c.id] != null -> "${typing[c.id]} is typing…"
                                c.isGroup && !c.joined -> c.joinError.ifBlank { "Not joined" }
                                c.isGroup && c.subject.isNotBlank() -> c.subject.lineSequence().first()
                                c.isGroup -> "${c.memberCount} members"
                                contact != null -> presenceText(contact)
                                else -> c.jid
                            }
                            Text(sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (c.type == "chat" && vm.settings.bool("calls.enabled")) {
                        IconButton(onClick = { startCall(false) }) {
                            Icon(Icons.Filled.Call, "Audio call")
                        }
                        IconButton(onClick = { startCall(true) }) {
                            Icon(Icons.Filled.Videocam, "Video call")
                        }
                    }
                    Icon(Icons.Filled.Lock, encryptionLabel(c), tint = MaterialTheme.colorScheme.primary)
                    if (c.isGroup) IconButton(onClick = { members = true }) { Icon(Icons.Filled.People, "Members") }
                    IconButton(onClick = { searchMode = !searchMode }) { Icon(Icons.Filled.Search, "Search") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; details = true })
                            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                                onClick = { menu = false; vm.setPinned(c, !c.pinned) })
                            DropdownMenuItem(text = { Text(if (c.archived) "Unarchive" else "Archive") }, leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = { menu = false; vm.setArchived(c, !c.archived) })
                            DropdownMenuItem(text = { Text(if (c.mutedUntil == -1L || c.mutedUntil > System.currentTimeMillis()) "Unmute" else "Mute") },
                                leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) },
                                onClick = { menu = false; vm.setMuted(c, if (c.mutedUntil == -1L) 0 else -1) })
                            DropdownMenuItem(text = { Text("Clear history…") }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                                onClick = { menu = false; vm.clearHistory(c) })
                            DropdownMenuItem(text = { Text(if (c.isGroup) "Leave group" else "Close chat") }, leadingIcon = { Icon(Icons.Filled.Logout, null) },
                                onClick = { menu = false; if (c.isGroup) vm.leaveGroup(c) else vm.closeChat(c) })
                        }
                    }
                }
            )
            if (searchMode) {
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search this chat") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = CircleShape)
            }
            }
        },
        bottomBar = {
            Composer(chat = c, text = text, onText = { text = it },
                replyTo = replyTo, editing = editing,
                onCancelReply = { replyTo = null; editing = null; text = c.draft },
                onSend = {
                    if (editing != null) { vm.edit(editing!!, text); editing = null }
                    else vm.send(text, replyTo)
                    replyTo = null; text = ""
                },
                onAttach = { attach.launch("*/*") },
                recording = recorder != null,
                sendWithEnter = (appSettings["chats.send_with"] ?: "enter") == "enter",
                onVoice = {
                    if (recorder != null) stopVoice()
                    else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                })
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (account != null && states[account.id]?.state != "online") {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WifiOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(states[account.id]?.error?.ifBlank { "You are offline. Messages you write are sent when you are back online." }
                            ?: "You are offline. Messages you write are sent when you are back online.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (states[account.id]?.state == "error" || states[account.id]?.state == "offline")
                            TextButton(onClick = { vm.connect(account) }) { Text("Connect") }
                    }
                }
            }
            if (c.isRequest) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${c.displayName} is not in your contacts. Messages from strangers can be spam.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.core.scope.launch { vm.core.acceptRequest(c.accountId, c.jid); vm.core.dao.updateChat(c.copy(isRequest = false)) } }) { Text("Accept") }
                        TextButton(onClick = { vm.block(c.accountId, c.jid, true) }) { Text("Block") }
                    }
                }
            }
            if (account != null && states[account.id]?.state == "locked") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Enter your password to connect.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { passwordOpen = true }) { Text("Connect") }
                    }
                }
            }
            if (c.isGroup && !c.joined) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(c.joinError.ifBlank { "You are not in this group right now" }, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.core.session(c.accountId)?.joinRoom(c) }) { Text("Join") }
                    }
                }
            }
            if (filtered.isEmpty()) {
                EmptyState(Icons.Outlined.ChatBubbleOutline, "No messages yet", "Say hello!")
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(filtered, key = { _, m -> m.id }) { index, m ->
                        val prev = if (index > 0) filtered[index - 1] else null
                        if (prev == null || TimeUtil.day(prev.timestamp) != TimeUtil.day(m.timestamp)) {
                            Text(TimeUtil.day(m.timestamp), modifier = Modifier.fillMaxWidth().padding(10.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        MessageItemRow(vm, c, m, prev, contact, modifier = Modifier,
                            onLongPress = { selectedMessage = m },
                            onReply = { replyTo = m; editing = null }, onReact = { vm.react(m, it) })
                    }
                }
            }
        }
    }
    if (selectedMessage != null) {
        MessageActionsSheet(vm, c, selectedMessage!!, onDismiss = { selectedMessage = null },
            onReply = { replyTo = it; editing = null; selectedMessage = null },
            onEdit = { editing = it; text = it.body; selectedMessage = null },
            onCopy = { context.copyText(it.body); selectedMessage = null },
            onDetails = { selectedMessage = null; messageDetails = it })
    }
    if (messageDetails != null) MessageDetailsDialog(c, messageDetails!!, onDismiss = { messageDetails = null })
    if (details) ChatDetailsSheet(vm, c, contact, onDismiss = { details = false }, onMembers = { details = false; members = true },
        onDevices = { details = false; devices = true })
    if (members) MembersSheet(vm, c, onDismiss = { members = false })
    if (devices) DevicesSheet(vm, c.accountId, c.jid, own = false, onDismiss = { devices = false })
    if (passwordOpen) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordOpen = false }, title = { Text("Password") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password for ${account?.jid}") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true) },
            confirmButton = { TextButton(onClick = { passwordOpen = false; account?.let { a -> vm.connect(a, pw); vm.core.scope.launch { vm.core.updateAccount(a.copy(encryptedPassword = org.ward.android.util.SecretStore.encrypt(pw))) } } }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { passwordOpen = false }) { Text("Cancel") } })
    }
}

fun android.content.Context.copyText(text: String) {
    val cm = getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Ward", text))
}

fun presenceText(c: ContactEntity): String = when (c.presence) {
    "available", "chat" -> c.status.ifBlank { "Available" }
    "away" -> c.status.ifBlank { "Away" }
    "dnd" -> c.status.ifBlank { "Busy" }
    "xa" -> c.status.ifBlank { "Away for a while" }
    else -> if (c.lastSeen > 0) "Last seen ${TimeUtil.relative(c.lastSeen)}" else "Offline"
}

fun encryptionLabel(c: ChatEntity): String = when (c.encryption) {
    "off" -> "Not encrypted"
    "pgp" -> "Encrypted with OpenPGP"
    "omemo2" -> "Encrypted with OMEMO 2"
    else -> "Encrypted with OMEMO"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItemRow(vm: AppViewModel, chat: ChatEntity, m: MessageEntity, prev: MessageEntity?,
                   contact: ContactEntity?, modifier: Modifier = Modifier,
                   onLongPress: () -> Unit, onReply: () -> Unit, onReact: (String) -> Unit) {
    val outgoing = m.direction == 1
    val grouping = prev != null && prev.direction == m.direction && prev.senderJid == m.senderJid &&
        prev.attachmentsJson == "[]" && m.timestamp - prev.timestamp < 5 * 60_000 && prev.retracted == 0
    val reactions = parseReactions(m.reactionsJson)
    val layout = vm.settings.string("appearance.layout", "cozy")
    val animateIn = remember(m.id) { System.currentTimeMillis() - m.timestamp < 2500 }
    val enter = remember(m.id) { androidx.compose.animation.core.Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(m.id) { if (animateIn) enter.animateTo(1f, androidx.compose.animation.core.tween(220)) }
    val bubble = layout == "bubbles"
    val name = if (chat.isGroup) m.senderName else if (outgoing) "You" else (contact?.name ?: m.senderName)
    val color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 45f }
            .padding(horizontal = 10.dp, vertical = if (grouping) 1.dp else 5.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        if (!outgoing && !grouping) Avatar(name, 34, show = if (chat.isGroup) null else contact?.presence) else if (!outgoing) Spacer(Modifier.width(34.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 560.dp)) {
            if (!grouping && !outgoing) {
                Text(name, color = parseColor(Colors.forJid(m.senderJid.ifBlank { m.sender })), fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, modifier = Modifier.padding(start = if (bubble) 10.dp else 0.dp))
            }
            val singleLine = m.body.isNotBlank() && !m.body.contains('\n') && m.body.length <= 42 &&
                m.attachmentsJson == "[]" && m.replyTo.isBlank() && m.retracted == 0
            val shape = when {
                !bubble -> RoundedCornerShape(12.dp)
                singleLine -> RoundedCornerShape(50)
                else -> RoundedCornerShape(26.dp)
            }
            Surface(color = color, shape = shape,
                tonalElevation = 0.dp,
                modifier = Modifier.combinedClickable(onLongClick = onLongPress, onClick = {})) {
                Column(Modifier.padding(horizontal = if (bubble) 14.dp else 8.dp, vertical = if (bubble) 10.dp else 4.dp)) {
                    if (m.replyTo.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text(m.replyAuthor.ifBlank { "Reply" }, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(m.replyPreview.ifBlank { "Message" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (m.retracted > 0) {
                        Text(if (m.retracted == 1 || m.retracted == 3) "This message was deleted" else "You deleted this message",
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    } else {
                        val atype = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                        val atts: List<Map<String, Any>> = vm.core.gson.fromJson(m.attachmentsJson, atype) ?: emptyList()
                        for (a in atts) {
                            AttachmentView(vm, a)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (m.body.isNotBlank()) MarkdownText(m.body, largeEmoji = vm.settings.bool("appearance.large_emoji"))
                        if (m.kind == "me") { /* already rendered with /me prefix */ }
                    }
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.edited) Text("edited", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.encryption.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Icon(if (m.trust == "verified") Icons.Filled.VerifiedUser else Icons.Filled.Lock, "Encrypted",
                                Modifier.size(11.dp), tint = if (m.trust == "verified") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(TimeUtil.clock(m.timestamp, vm.settings.bool("appearance.time_seconds"),
                            vm.settings.string("appearance.time_format") == "12"), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (outgoing) {
                            Spacer(Modifier.width(4.dp))
                            Icon(when (m.status) { -1 -> Icons.Filled.Error; 3 -> Icons.Filled.DoneAll; 2 -> Icons.Filled.DoneAll; else -> Icons.Filled.Done },
                                "Status", Modifier.size(13.dp), tint = if (m.status >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (reactions.isNotEmpty()) {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    reactions.forEach { r ->
                        Surface(shape = CircleShape, color = if (r.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { r.emoji.forEach { onReact(it) } }) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.emoji.joinToString(""), fontSize = 13.sp)
                                Text(" ${r.who}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (outgoing && !grouping) Spacer(Modifier.width(4.dp)) else if (outgoing) Spacer(Modifier.width(42.dp))
    }
}

private fun formatSize(size: Long): String = when {
    size > 1_000_000 -> "%.1f MB".format(size / 1_000_000.0)
    size > 1_000 -> "%.0f kB".format(size / 1_000.0)
    else -> "$size B"
}

@Composable
fun Composer(chat: ChatEntity, text: String, onText: (String) -> Unit, replyTo: MessageEntity?, editing: MessageEntity?,
             onCancelReply: () -> Unit, onSend: () -> Unit, onAttach: () -> Unit,
             recording: Boolean = false,
             sendWithEnter: Boolean = true,
             onVoice: () -> Unit = {}) {
    val enterModifier = if (sendWithEnter) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                if (text.isNotBlank()) onSend()
                true
            } else false
        }
    } else Modifier
    Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
        Column {
            AnimatedVisibility(visible = replyTo != null || editing != null,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editing != null) Icons.Filled.Edit else Icons.Filled.Reply, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Edit message" else "Reply to ${replyTo?.senderName}",
                            fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text((editing ?: replyTo)?.body?.lineSequence()?.firstOrNull() ?: "", maxLines =  sky(1),
                            overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, "Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, "Attach") }
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(text, onText, placeholder = {
                            Text(if (chat.encryption == "off") "Message" else "Encrypted message")
                        }, modifier = Modifier.weight(1f).padding(0.dp).then(enterModifier), maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = if (sendWithEnter) ImeAction.Send else ImeAction.Default),
                            keyboardActions = if (sendWithEnter) KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }) else KeyboardActions(),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent))
                    }
                }
                IconButton(onClick = onVoice) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, if (recording) "Stop recording" else "Voice message",
                        tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(2.dp))
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank() && !recording, modifier = Modifier.size(48.dp), shape = CircleShape) {
                    Icon(Icons.Filled.Send, "Send")
                }
            }
        }
    }
}
private fun sky(n: Int) = n

@Composable
private fun MessageDetailsDialog(chat: ChatEntity, msg: MessageEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enc = when (msg.encryption) {
        "omemo2" -> "OMEMO 2"
        "omemo" -> "OMEMO"
        "pgp" -> "OpenPGP"
        else -> "None"
    }
    val time = TimeUtil.day(msg.timestamp) + " " + TimeUtil.clock(msg.timestamp, true,
        false)
    fun copy(value: String) {
        if (value.isBlank()) return
        context.copyText(value)
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                DetailRow("Encryption", enc, ::copy)
                DetailRow("Sender", msg.senderJid.ifBlank { msg.sender }, ::copy)
                if (msg.senderDevice != 0L) DetailRow("Sender device", msg.senderDevice.toString(), ::copy)
                if (msg.trust.isNotBlank()) DetailRow("Trust", msg.trust.replaceFirstChar { it.uppercase() }, ::copy)
                DetailRow("Time", time, ::copy)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DetailRow("Server id", msg.stanzaId.ifBlank { "—" }, ::copy)
                DetailRow("Origin id", msg.originId.ifBlank { "—" }, ::copy)
                DetailRow("Message id", msg.msgId.ifBlank { "—" }, ::copy)
                if (chat.isGroup && msg.occupantId.isNotBlank()) DetailRow("Occupant id", msg.occupantId, ::copy)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun DetailRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { onCopy(value) }, enabled = value.isNotBlank() && value != "—") {
            Icon(Icons.Filled.ContentCopy, "Copy $label")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(vm: AppViewModel, chat: ChatEntity, msg: MessageEntity, onDismiss: () -> Unit,
                        onReply: (MessageEntity) -> Unit, onEdit: (MessageEntity) -> Unit, onCopy: (MessageEntity) -> Unit,
                        onDetails: (MessageEntity) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (e in vm.settings.list("chats.quick_reactions").ifEmpty { listOf("👍", "❤️", "😂", "😮", "😢", "🎉") }) {
                    TextButton(onClick = { vm.react(msg, e); onDismiss() }) { Text(e, fontSize = 26.sp) }
                }
            }
            HorizontalDivider()
            if (msg.retracted == 0) {
                SheetAction(Icons.Filled.Reply, "Reply") { onReply(msg) }
                if (msg.direction == 1 && msg.kind != "file") SheetAction(Icons.Filled.Edit, "Edit") { onEdit(msg) }
                SheetAction(Icons.Filled.ContentCopy, "Copy text") { onCopy(msg) }
                SheetAction(Icons.Filled.Forward, "Forward") { onDismiss() }
                if (msg.direction == 1) SheetAction(Icons.Filled.Delete, "Delete for everyone") { confirmDelete = true }
                SheetAction(Icons.Filled.DeleteOutline, "Delete for me") {
                    vm.retract(msg, false); onDismiss()
                }
                SheetAction(Icons.Filled.Info, "Message details") { onDetails(msg) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete for everyone?") },
        text = { Text("This removes the message for everyone in this chat.") },
        confirmButton = { TextButton(onClick = { vm.retract(msg, true); confirmDelete = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailsSheet(vm: AppViewModel, chat: ChatEntity, contact: ContactEntity?, onDismiss: () -> Unit, onMembers: () -> Unit, onDevices: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var topic by remember { mutableStateOf(chat.subject) }
    var inviteOpen by remember { mutableStateOf(false) }
    var inviteJid by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf(chat.notify) }
    var encryption by remember { mutableStateOf(chat.encryption) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Avatar(chat.displayName, 72, show = if (chat.isGroup) null else contact?.presence)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(chat.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(chat.jid, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (contact != null) Text(presenceText(contact), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (chat.isGroup) {
                OutlinedTextField(topic, { topic = it }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setSubject(chat, topic); onDismiss() }) { Text("Save topic") }
                    OutlinedButton(onClick = onMembers) { Text("${chat.memberCount} members") }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Text("Notifications", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("default" to "Default", "all" to "All", "mentions" to "Mentions", "none" to "None").forEach { (v, l) ->
                    FilterChip(selected = notify == v, onClick = { notify = v; vm.setNotify(chat, v) }, label = { Text(l) })
                }
            }
            if (!chat.isGroup) {
                ListItem(headlineContent = { Text("Devices and keys") }, supportingContent = { Text("Fingerprints and verification") },
                    leadingContent = { Icon(Icons.Filled.Key, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.clickable { onDevices() })
                Text("Encryption", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (listOf("default" to "Default", "omemo2" to "OMEMO 2", "omemo" to "Legacy OMEMO", "off" to "Off") +
                        if (vm.settings.bool("pgp.enabled")) listOf("pgp" to "OpenPGP") else emptyList()).forEach { (v, l) ->
                        FilterChip(selected = encryption == v, onClick = { encryption = v; vm.setEncryption(chat, v) }, label = { Text(l) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            if (contact != null) {
                ListItem(headlineContent = { Text(if (contact.blocked) "Unblock" else "Block…") }, leadingContent = { Icon(Icons.Filled.Block, null) },
                    modifier = Modifier.clickable { vm.block(chat.accountId, chat.jid, !contact.blocked); onDismiss() })
                ListItem(headlineContent = { Text("Remove from contacts…") }, leadingContent = { Icon(Icons.Filled.PersonRemove, null) },
                    modifier = Modifier.clickable { vm.removeContact(chat.accountId, chat.jid); onDismiss() })
            }
            ListItem(headlineContent = { Text("Clear history…") }, leadingContent = { Icon(Icons.Filled.DeleteSweep, null) },
                modifier = Modifier.clickable { vm.clearHistory(chat); onDismiss() })
            if (chat.isGroup) {
                ListItem(headlineContent = { Text("Invite…") }, supportingContent = { Text("xmpp:${chat.jid}?join") },
                    leadingContent = { Icon(Icons.Filled.PersonAdd, null) }, modifier = Modifier.clickable { inviteOpen = true })
                ListItem(headlineContent = { Text("Leave group") }, leadingContent = { Icon(Icons.Filled.Logout, null) },
                    modifier = Modifier.clickable { vm.leaveGroup(chat); onDismiss() })
            }
        }
    }
    if (inviteOpen) AlertDialog(onDismissRequest = { inviteOpen = false }, title = { Text("Invite to ${chat.displayName}") }, text = {
        Column {
            OutlinedTextField(inviteJid, { inviteJid = it }, label = { Text("Address") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Text("Or share this link: xmpp:${chat.jid}?join", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { if (inviteJid.isNotBlank()) vm.core.session(chat.accountId)?.invite(chat, inviteJid); inviteOpen = false }) { Text("Invite") }
    }, dismissButton = { TextButton(onClick = { inviteOpen = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersSheet(vm: AppViewModel, chat: ChatEntity, onDismiss: () -> Unit) {
    val occupants = vm.core.session(chat.accountId)?.occupants?.collectAsState()?.value?.get(chat.jid) ?: emptyList()
    var selected by remember { mutableStateOf<OccupantInfo?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${chat.memberCount} members", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDismiss() }) { Text("Done") }
            }
            LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(occupants, key = { it.nick }) { o ->
                    ListItem(headlineContent = { Text(o.nick + if (o.isSelf) " (you)" else "") },
                        supportingContent = { Text(listOf(o.role, o.affiliation).filter { it != "none" }.joinToString(", ")) },
                        leadingContent = { Avatar(o.nick, 36, show = if (o.status.isBlank()) "available" else "away") },
                        trailingContent = { if (o.role == "moderator") Icon(Icons.Filled.Star, "Moderator", tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { selected = o })
                }
            }
        }
    }
    if (selected != null) {
        val o = selected!!
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(o.nick) }, text = {
            Column {
                Text("Role: ${o.role}\nAffiliation: ${o.affiliation}")
                Spacer(Modifier.height(8.dp))
                if (o.role == "moderator") TextButton(onClick = { vm.core.session(chat.accountId)?.setRole(chat, o.nick, "participant"); selected = null }) { Text("Remove moderator") }
                else TextButton(onClick = { vm.core.session(chat.accountId)?.setRole(chat, o.nick, "moderator"); selected = null }) { Text("Make moderator") }
                TextButton(onClick = { vm.core.session(chat.accountId)?.setRole(chat, o.nick, "visitor"); selected = null }) { Text("Revoke voice") }
                TextButton(onClick = { vm.core.session(chat.accountId)?.kick(chat, o.nick); selected = null }) { Text("Kick") }
                if (o.jid.isNotBlank()) TextButton(onClick = { vm.core.session(chat.accountId)?.ban(chat, o.jid); selected = null }) { Text("Ban") }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
}
