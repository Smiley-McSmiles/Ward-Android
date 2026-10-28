package org.ward.android.ui

import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ward.android.data.*
import org.ward.android.parseReactions
import org.ward.android.util.AppLockStore
import org.ward.android.util.Colors
import org.ward.android.util.TimeUtil
import org.ward.android.xmpp.OccupantInfo
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    val chat by vm.selectedChatEntity.collectAsState()
    val messages by vm.messages.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val typing by vm.typing.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val allChats by vm.chats.collectAsState()
    val states by vm.states.collectAsState()
    val appSettings by vm.settings.state.collectAsState()
    val c = chat ?: return
    val account = accounts.firstOrNull { it.id == c.accountId }
    val contact = contacts.firstOrNull { it.accountId == c.accountId && it.jid == c.jid }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val maxSwipe = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    fun closeChatAnimated() {
        scope.launch {
            animate(swipeOffset, maxSwipe, animationSpec = tween(220)) { value, _ -> swipeOffset = value }
            vm.selectChat(null)
        }
    }
    var searchMode by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var messageDetails by remember { mutableStateOf<MessageEntity?>(null) }
    var members by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var devices by remember { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }
    var text by remember(c.id) { mutableStateOf(c.draft) }
    var initialized by remember(c.id) { mutableStateOf(false) }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        AppLockStore.endExternalActivity()
        if (uris.isNotEmpty()) vm.sendFiles(uris)
    }
    var recorder by remember(c.id) { mutableStateOf<MediaRecorder?>(null) }
    var voiceStartedAt by remember(c.id) { mutableLongStateOf(0L) }
    // Keep the rapidly changing waveform in a State object and read it only inside the composer,
    // so a 12 fps recording preview does not recompose the whole message list.
    val voiceState = remember(c.id) { mutableStateOf(VoiceUiState()) }
    fun currentVoice() = voiceState.value
    var composerEmoji by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun releaseVoiceResources() {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
    }

    fun startVoice() {
        if (recorder != null) return
        currentVoice().file?.delete()
        val f = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        var r: MediaRecorder? = null
        try {
            r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            // VOICE_COMMUNICATION asks the platform for its voice-processing path, which normally
            // includes noise suppression and automatic gain control while recording a voice note.
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            voiceStartedAt = SystemClock.elapsedRealtime()
            voiceState.value = VoiceUiState(open = true, phase = VoicePhase.Recording, file = f,
                noiseCancellation = true)
        } catch (e: Exception) {
            runCatching { r?.release() }
            f.delete()
            voiceState.value = VoiceUiState(open = true,
                error = "Could not start recording: ${e.message ?: "microphone unavailable"}")
        }
    }

    fun stopVoice() {
        val r = recorder ?: return
        recorder = null
        val elapsed = (SystemClock.elapsedRealtime() - voiceStartedAt).coerceAtLeast(0L)
        var failed = false
        runCatching { r.stop() }.onFailure { failed = true }
        runCatching { r.release() }
        val v = currentVoice()
        val f = v.file
        if (failed || f == null || f.length() <= 1000L) {
            f?.delete()
            voiceState.value = v.copy(phase = VoicePhase.Idle, file = null, elapsedMs = 0L,
                waveform = emptyList(), noiseCancellation = null,
                error = "That recording was too short. Tap the microphone and try again.")
        } else {
            voiceState.value = v.copy(phase = VoicePhase.Review, elapsedMs = elapsed,
                noiseCancellation = null, error = "")
        }
    }

    fun restartVoice() {
        currentVoice().file?.delete()
        releaseVoiceResources()
        voiceState.value = currentVoice().copy(phase = VoicePhase.Idle, file = null,
            elapsedMs = 0L, waveform = emptyList(), noiseCancellation = null, error = "")
        startVoice()
    }

    fun cancelVoice() {
        releaseVoiceResources()
        currentVoice().file?.delete()
        voiceState.value = VoiceUiState()
    }

    fun attachVoice() {
        val f = currentVoice().file ?: return
        if (f.length() <= 1000L) {
            voiceState.value = currentVoice().copy(error = "That recording is too short to send.")
            return
        }
        // The outbox copies the file before uploading, but keep the cache file around until the
        // upload has read it. The document cache is cleaned up by the system later.
        vm.sendFile(Uri.fromFile(f))
        voiceState.value = VoiceUiState()
    }

    fun toggleVoice() {
        val v = currentVoice()
        when {
            !v.open -> {
                keyboard?.hide()
                focusManager.clearFocus()
                voiceState.value = VoiceUiState(open = true)
            }
            v.phase == VoicePhase.Recording -> stopVoice()
            else -> cancelVoice()
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice()
        else voiceState.value = currentVoice().copy(open = true,
            error = "Microphone permission is required to record voice messages.")
    }

    fun handleVoiceAction(action: VoiceAction) {
        when (action) {
            VoiceAction.Start -> if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoice()
            } else {
                micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            VoiceAction.Stop -> stopVoice()
            VoiceAction.Restart -> restartVoice()
            VoiceAction.Cancel -> cancelVoice()
            VoiceAction.Attach -> attachVoice()
        }
    }

    DisposableEffect(c.id) {
        onDispose {
            recorder?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
            recorder = null
            currentVoice().file?.delete()
        }
    }

    // Key only on the recorder, not on the rapidly changing VoiceUiState, so updating the
    // waveform does not invalidate this whole screen's composition scope.
    LaunchedEffect(recorder) {
        val activeRecorder = recorder ?: return@LaunchedEffect
        if (voiceState.value.phase != VoicePhase.Recording) return@LaunchedEffect
        val startedAt = voiceStartedAt
        while (true) {
            if (voiceState.value.phase != VoicePhase.Recording) break
            val amplitude = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
            val level = (amplitude / 32767f).coerceIn(0f, 1f)
            val v = voiceState.value
            voiceState.value = v.copy(
                elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                waveform = (v.waveform + level).takeLast(48)
            )
            delay(80)
        }
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
        if (searchMode) searchMode = false else if (selectedMessage != null) selectedMessage = null else closeChatAnimated()
    }
    LaunchedEffect(c.id) {
        swipeOffset = 0f
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
        modifier = Modifier.fillMaxSize().imePadding()
            .offset { IntOffset(swipeOffset.roundToInt(), 0) },
        topBar = {
            // Scaffold expects a single top-bar composable. Emitting the app bar and the search
            // field as siblings makes Scaffold place both at y=0, which hides the search field
            // under the status bar. A Column keeps them stacked and lets Scaffold measure both.
            Column(Modifier.fillMaxWidth()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { WardBackButton { closeChatAnimated() } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { details = true }) {
                        Avatar(c.displayName, 34, photo = if (c.isGroup) c.avatar else contact?.avatar ?: c.avatar,
                            show = if (c.isGroup) null else contact?.presence)
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
                    if (currentVoice().open) cancelVoice()
                    if (editing != null) { vm.edit(editing!!, text); editing = null }
                    else vm.send(text, replyTo)
                    replyTo = null; text = ""
                },
                onAttach = {
                    if (currentVoice().open) cancelVoice()
                    AppLockStore.beginExternalActivity()
                    attach.launch(arrayOf("*/*"))
                },
                onAttachImages = {
                    if (currentVoice().open) cancelVoice()
                    AppLockStore.beginExternalActivity()
                    attach.launch(arrayOf("image/*"))
                },
                onEmoji = {
                    if (currentVoice().open) cancelVoice()
                    composerEmoji = true
                },
                sendWithEnter = (appSettings["chats.send_with"] ?: "enter") == "enter",
                voiceState = voiceState,
                onVoiceClick = { toggleVoice() },
                onVoiceAction = { handleVoiceAction(it) })
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
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(maxSwipe) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            var dragging = false
                            var totalX = 0f
                            var totalY = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y
                                if (!dragging) {
                                    if (abs(totalY) > viewConfiguration.touchSlop &&
                                        abs(totalY) > abs(totalX)) break
                                    if (totalX > viewConfiguration.touchSlop && totalX > abs(totalY)) {
                                        dragging = true
                                        change.consume()
                                    }
                                }
                                if (dragging) {
                                    change.consume()
                                    swipeOffset = (swipeOffset + delta.x).coerceIn(0f, maxSwipe)
                                }
                            }
                            if (dragging) {
                                val shouldClose = swipeOffset > maxSwipe * 0.28f
                                val target = if (shouldClose) maxSwipe else 0f
                                scope.launch {
                                    animate(swipeOffset, target, animationSpec = tween(220)) { value, _ ->
                                        swipeOffset = value
                                    }
                                    if (shouldClose) vm.selectChat(null)
                                }
                            }
                        }
                    }
            ) {
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
    }
    if (selectedMessage != null) {
        MessageActionsSheet(vm, c, selectedMessage!!, onDismiss = { selectedMessage = null },
            onReply = { replyTo = it; editing = null; selectedMessage = null },
            onEdit = { editing = it; text = it.body; selectedMessage = null },
            onCopy = { context.copyText(it.body); selectedMessage = null },
            onForward = { forwardMessage = it; selectedMessage = null },
            onDetails = { selectedMessage = null; messageDetails = it })
    }
    if (forwardMessage != null) {
        ForwardMessageDialog(vm, forwardMessage!!, allChats, accounts,
            onDismiss = { forwardMessage = null })
    }
    if (messageDetails != null) MessageDetailsDialog(c, messageDetails!!, onDismiss = { messageDetails = null })
    if (details) ChatDetailsSheet(vm, c, contact, onDismiss = { details = false }, onMembers = { details = false; members = true },
        onDevices = { details = false; devices = true })
    if (members) MembersSheet(vm, c, onDismiss = { members = false })
    if (devices) DevicesSheet(vm, c.accountId, c.jid, own = false, onDismiss = { devices = false })
    if (composerEmoji) {
        EmojiPickerDialog(vm, title = "Insert emoji", onDismiss = { composerEmoji = false }) { emoji ->
            text += emoji
            composerEmoji = false
        }
    }
    if (passwordOpen) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordOpen = false }, title = { Text("Password") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password for ${account?.jid}") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true) },
            confirmButton = { TextButton(onClick = {
                passwordOpen = false
                account?.let { a ->
                    vm.connect(a, pw)
                    org.ward.android.util.SecretStore.encryptOrNull(pw)?.let { stored ->
                        vm.core.scope.launch { vm.core.updateAccount(a.copy(encryptedPassword = stored)) }
                    }
                }
            }) { Text("Connect") } },
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
    val context = LocalContext.current
    val grouping = prev != null && prev.direction == m.direction && prev.senderJid == m.senderJid &&
        prev.attachmentsJson == "[]" && m.timestamp - prev.timestamp < 5 * 60_000 && prev.retracted == 0
    val reactions = parseReactions(m.reactionsJson)
    val layout = vm.settings.string("appearance.layout", "cozy")
    val compact = layout == "compact"
    val avatarMode = vm.settings.string("appearance.avatars", "always")
    val showMessageAvatar = (avatarMode == "always" || (avatarMode == "groups" && chat.isGroup)) && !compact
    val showSender = !compact
    val animateIn = remember(m.id) {
        vm.settings.bool("appearance.message_animations") && System.currentTimeMillis() - m.timestamp < 2500
    }
    val enter = remember(m.id) { androidx.compose.animation.core.Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(m.id) { if (animateIn) enter.animateTo(1f, androidx.compose.animation.core.tween(220)) }
    val bubble = layout == "bubbles"
    val name = if (chat.isGroup) m.senderName else if (outgoing) "You" else (contact?.name ?: m.senderName)
    val color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else wardPanelColor()
    Row(modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 45f }
            .padding(horizontal = 10.dp, vertical = if (grouping || compact) 1.dp else 5.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        if (!outgoing && !grouping && showMessageAvatar) Avatar(name, 34,
            photo = if (chat.isGroup) chat.avatar else contact?.avatar ?: chat.avatar,
            show = if (chat.isGroup) null else contact?.presence)
        else if (!outgoing && (grouping || !showMessageAvatar)) Spacer(Modifier.width(if (grouping) 34.dp else 0.dp))
        Spacer(Modifier.width(if (showMessageAvatar || outgoing) 8.dp else 0.dp))
        Column(Modifier.widthIn(max = 560.dp)) {
            if (!grouping && !outgoing && showSender) {
                Text(name, color = parseColor(Colors.forJid(m.senderJid.ifBlank { m.sender })), fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, modifier = Modifier.padding(start = if (bubble) 10.dp else 0.dp))
            }
            val singleLine = m.body.isNotBlank() && !m.body.contains('\n') && m.body.length <= 42 &&
                m.attachmentsJson == "[]" && m.replyTo.isBlank() && m.retracted == 0
            val shape = when {
                !bubble -> RoundedCornerShape(if (compact) 8.dp else 12.dp)
                singleLine -> RoundedCornerShape(50)
                else -> RoundedCornerShape(26.dp)
            }
            var swipeX by remember(m.id) { mutableFloatStateOf(0f) }
            val maxSwipe = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
            val replyThreshold = maxSwipe * 0.65f
            val swipeModifier = if (m.retracted == 0) Modifier
                .offset { IntOffset(swipeX.roundToInt(), 0) }
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            swipeX = (swipeX + amount).coerceIn(0f, maxSwipe)
                        },
                        onDragEnd = {
                            if (swipeX >= replyThreshold) onReply()
                            swipeX = 0f
                        },
                        onDragCancel = { swipeX = 0f }
                    )
                } else Modifier
            Surface(color = color, shape = shape,
                tonalElevation = 0.dp,
                modifier = swipeModifier.combinedClickable(onLongClick = onLongPress, onClick = {})) {
                Column(Modifier.padding(horizontal = if (bubble) 14.dp else 8.dp,
                    vertical = if (bubble) 10.dp else if (compact) 2.dp else 4.dp)) {
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
                        val attachmentNames = atts.mapNotNull { it["name"] as? String }
                        if (m.kind == "me") {
                            val action = m.body.removePrefix("/me ").trimStart()
                            if (action.isNotBlank()) {
                                val actionName = m.senderName.ifBlank { name }
                                Row(Modifier.padding(vertical = 1.dp)) {
                                    Text(actionName,
                                        color = if (outgoing) MaterialTheme.colorScheme.primary
                                        else parseColor(Colors.forJid(m.senderJid.ifBlank { m.sender })),
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(" $action", color = MaterialTheme.colorScheme.onSurface,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            }
                        } else if (m.body.isNotBlank() && m.body !in attachmentNames) {
                            MarkdownText(m.body, largeEmoji = vm.settings.bool("appearance.large_emoji"),
                                onLongPress = onLongPress)
                        }
                        if (m.status == -1 && m.error.isNotBlank()) {
                            Text(m.error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.edited) Text("edited", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.encryption.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Icon(if (m.trust == "verified") Icons.Filled.VerifiedUser else Icons.Filled.Lock, "Encrypted",
                                Modifier.size(11.dp), tint = if (m.trust == "verified") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(TimeUtil.clock(m.timestamp, LocalTimeSeconds.current,
                            TimeUtil.twelveHour(context, LocalTimeFormat.current)), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (outgoing) {
                            Spacer(Modifier.width(4.dp))
                            Icon(when (m.status) { -1 -> Icons.Filled.Error; 0 -> Icons.Filled.Schedule; 3 -> Icons.Filled.DoneAll; 2 -> Icons.Filled.DoneAll; else -> Icons.Filled.Done },
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

enum class VoicePhase { Idle, Recording, Review }
enum class VoiceAction { Start, Stop, Restart, Cancel, Attach }

data class VoiceUiState(
    val open: Boolean = false,
    val phase: VoicePhase = VoicePhase.Idle,
    val elapsedMs: Long = 0L,
    val waveform: List<Float> = emptyList(),
    val noiseCancellation: Boolean? = null,
    val file: File? = null,
    val error: String = ""
)

@Composable
fun Composer(chat: ChatEntity, text: String, onText: (String) -> Unit, replyTo: MessageEntity?, editing: MessageEntity?,
             onCancelReply: () -> Unit, onSend: () -> Unit, onAttach: () -> Unit,
             onAttachImages: () -> Unit = {}, onEmoji: () -> Unit = {},
             sendWithEnter: Boolean = true,
             voiceState: State<VoiceUiState>,
             onVoiceClick: () -> Unit = {},
             onVoiceAction: (VoiceAction) -> Unit = {}) {
    val voice by voiceState
    val enterModifier = if (sendWithEnter) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                if (text.isNotBlank()) onSend()
                true
            } else false
        }
    } else Modifier
    // The composer sits directly over the chat background; only the input pill and the open
    // recorder card should be visible, not a full-width panel behind them.
    Surface(color = Color.Transparent, tonalElevation = 0.dp,
        modifier = Modifier.navigationBarsPadding()) {
        Column {
            AnimatedVisibility(visible = replyTo != null || editing != null,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editing != null) Icons.Filled.Edit else Icons.Filled.Reply, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Edit message" else "Reply to ${replyTo?.senderName}",
                            fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text((editing ?: replyTo)?.body?.lineSequence()?.firstOrNull() ?: "", maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, "Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onAttach, enabled = !voice.open) {
                            Icon(Icons.Filled.AddCircleOutline, "Attach files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onText,
                            modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                                .onFocusChanged { if (it.isFocused && voice.open) onVoiceAction(VoiceAction.Cancel) }
                                .then(enterModifier),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = if (sendWithEnter) ImeAction.Send else ImeAction.Default),
                            keyboardActions = if (sendWithEnter) KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }) else KeyboardActions(),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) {
                                        Text(if (chat.encryption == "off") "Message" else "Encrypted message",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    inner()
                                }
                            })
                        IconButton(onClick = onEmoji, enabled = !voice.open) {
                            Icon(Icons.Outlined.EmojiEmotions, "Insert emoji",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (text.isBlank()) {
                            IconButton(onClick = onAttachImages, enabled = !voice.open) {
                                Icon(Icons.Outlined.Image, "Send image",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            FilledIconButton(onClick = onSend, enabled = !voice.open,
                                modifier = Modifier.size(38.dp), shape = CircleShape) {
                                Icon(Icons.Filled.Send, "Send", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onVoiceClick, modifier = Modifier.size(52.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (voice.open) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (voice.open) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Icon(Icons.Filled.GraphicEq,
                        if (voice.open) "Close voice recorder" else "Record a voice message")
                }
            }
            AnimatedVisibility(visible = voice.open,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                VoiceRecorderPanel(voice, onVoiceAction)
            }
        }
    }
}

private fun formatVoiceTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
private fun VoiceRecorderPanel(voice: VoiceUiState, onAction: (VoiceAction) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            when (voice.phase) {
                VoicePhase.Idle -> IdleVoiceCard()
                VoicePhase.Recording -> RecordingVoiceCard(voice)
                VoicePhase.Review -> ReviewVoiceCard(voice)
            }
            if (voice.error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(voice.error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            VoiceControls(voice, onAction)
        }
    }
}

@Composable
private fun IdleVoiceCard() {
    Column(Modifier.fillMaxWidth().height(210.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(84.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, null, Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Tap to record your voice", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Listen before you send.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordingVoiceCard(voice: VoiceUiState) {
    Column(Modifier.fillMaxWidth().height(210.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(formatVoiceTime(voice.elapsedMs), fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(14.dp))
        VoiceWaveform(voice.waveform, active = true,
            modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp))
        Spacer(Modifier.height(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (voice.noiseCancellation == true) "Noise cancellation ON" else "Noise cancellation unavailable",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ReviewVoiceCard(voice: VoiceUiState) {
    val file = voice.file
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    DisposableEffect(file) {
        onDispose { player?.release(); player = null }
    }
    fun togglePlayback() {
        val f = file ?: return
        if (playing) {
            player?.pause()
            playing = false
            return
        }
        val p = player ?: runCatching {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { playing = false; it.seekTo(0) }
            }
        }.getOrNull() ?: return
        player = p
        p.start()
        playing = true
    }
    Row(Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = ::togglePlayback, enabled = file != null,
            modifier = Modifier.size(58.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Pause recording" else "Play recording")
        }
        Spacer(Modifier.width(12.dp))
        VoiceWaveform(voice.waveform, active = false,
            modifier = Modifier.weight(1f).height(62.dp))
        Spacer(Modifier.width(12.dp))
        Text(formatVoiceTime(voice.elapsedMs), fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    }
}

@Composable
private fun VoiceWaveform(samples: List<Float>, active: Boolean, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val bars = 44
    val data = remember(samples, active) {
        val tail = samples.takeLast(bars)
        if (tail.size >= bars) tail else List(bars - tail.size) { 0f } + tail
    }
    Canvas(modifier) {
        val step = size.width / bars
        val base = 1.5.dp.toPx()
        // Keep the dots from overlapping when the waveform is wide.
        val max = minOf(size.height * 0.34f, step * 0.40f)
        val centerY = size.height / 2f
        data.forEachIndexed { i, raw ->
            val level = raw.coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = if (active) 0.9f else 0.6f),
                base + max * level, Offset(step * (i + 0.5f), centerY))
        }
    }
}

@Composable
private fun VoiceControls(voice: VoiceUiState, onAction: (VoiceAction) -> Unit) {
    val idle = voice.phase == VoicePhase.Idle
    val recording = voice.phase == VoicePhase.Recording
    val review = voice.phase == VoicePhase.Review
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceTextButton(
            label = if (idle) "Cancel" else "Restart",
            icon = if (idle) Icons.Filled.Close else Icons.Filled.Replay,
            onClick = { onAction(if (idle) VoiceAction.Cancel else VoiceAction.Restart) },
            modifier = Modifier.weight(1f)
        )
        VoiceRoundButton(
            icon = if (idle) Icons.Filled.Mic else Icons.Filled.Stop,
            onClick = {
                if (idle) onAction(VoiceAction.Start)
                else if (recording) onAction(VoiceAction.Stop)
            },
            modifier = Modifier.weight(1.15f),
            container = when {
                idle -> MaterialTheme.colorScheme.primaryContainer
                recording -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            content = when {
                idle -> MaterialTheme.colorScheme.onPrimaryContainer
                recording -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            enabled = idle || recording
        )
        VoiceTextButton(
            label = "Attach",
            icon = Icons.Filled.Check,
            onClick = { onAction(VoiceAction.Attach) },
            modifier = Modifier.weight(1f),
            enabled = review,
            filled = review
        )
    }
}

@Composable
private fun VoiceTextButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                            onClick: () -> Unit, modifier: Modifier = Modifier,
                            enabled: Boolean = true, filled: Boolean = false) {
    val contentPadding = PaddingValues(horizontal = 10.dp)
    val shape = RoundedCornerShape(28.dp)
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.height(54.dp),
            shape = shape, contentPadding = contentPadding) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(54.dp),
            shape = shape, contentPadding = contentPadding) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun VoiceRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector,
                             onClick: () -> Unit, modifier: Modifier = Modifier,
                             container: Color, content: Color, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)) {
        Icon(icon, null, Modifier.size(24.dp))
    }
}

@Composable
private fun MessageDetailsDialog(chat: ChatEntity, msg: MessageEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enc = when (msg.encryption) {
        "omemo2" -> "OMEMO 2"
        "omemo" -> "Legacy OMEMO"
        "pgp" -> "OpenPGP"
        else -> "None"
    }
    val time = TimeUtil.day(msg.timestamp) + " " + TimeUtil.clock(msg.timestamp, true,
        TimeUtil.twelveHour(context, LocalTimeFormat.current))
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
                        onForward: (MessageEntity) -> Unit, onDetails: (MessageEntity) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var emojiPicker by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val tone = vm.settings.string("chats.reaction_skin_tone", "")
                for (e in vm.settings.list("chats.quick_reactions").ifEmpty { listOf("👍", "❤️", "😂", "😮", "😢", "🎉") }) {
                    val reaction = applySkinTone(e, tone)
                    TextButton(onClick = { vm.react(msg, reaction); onDismiss() }) { Text(reaction, fontSize = 26.sp) }
                }
                IconButton(onClick = { emojiPicker = true }) {
                    Icon(Icons.Filled.AddReaction, "More reactions")
                }
            }
            HorizontalDivider()
            if (msg.retracted == 0) {
                SheetAction(Icons.Filled.Reply, "Reply") { onReply(msg) }
                if (msg.direction == 1 && msg.kind != "file") SheetAction(Icons.Filled.Edit, "Edit") { onEdit(msg) }
                SheetAction(Icons.Filled.ContentCopy, "Copy text") { onCopy(msg) }
                SheetAction(Icons.Filled.Forward, "Forward") { onForward(msg) }
                if (msg.direction == 1) SheetAction(Icons.Filled.Delete, "Delete for everyone") {
                    if (vm.settings.bool("chats.confirm_delete")) confirmDelete = true else { vm.retract(msg, true); onDismiss() }
                }
                SheetAction(Icons.Filled.DeleteOutline, "Delete for me") {
                    vm.retract(msg, false); onDismiss()
                }
                SheetAction(Icons.Filled.Info, "Message details") { onDetails(msg) }
            }
        }
    }
    if (emojiPicker) {
        EmojiPickerDialog(vm, onDismiss = { emojiPicker = false }) { emoji ->
            emojiPicker = false
            vm.react(msg, emoji)
            onDismiss()
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete for everyone?") },
        text = { Text("This removes the message for everyone in this chat.") },
        confirmButton = { TextButton(onClick = { vm.retract(msg, true); confirmDelete = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun EmojiPickerDialog(vm: AppViewModel, title: String = "Choose a reaction",
                              onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var tone by remember { mutableStateOf(vm.settings.string("chats.reaction_skin_tone", "")) }
    var toneMenu by remember { mutableStateOf(false) }
    val tones = listOf(
        "" to "\u270B", "\uD83C\uDFFB" to "\u270B\uD83C\uDFFB", "\uD83C\uDFFC" to "\u270B\uD83C\uDFFC",
        "\uD83C\uDFFD" to "\u270B\uD83C\uDFFD", "\uD83C\uDFFE" to "\u270B\uD83C\uDFFE",
        "\uD83C\uDFFF" to "\u270B\uD83C\uDFFF"
    )
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("Skin tone", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Box {
                        TextButton(onClick = { toneMenu = true }) {
                            Text(tones.firstOrNull { it.first == tone }?.second ?: "\u270B", fontSize = 22.sp)
                            Icon(Icons.Filled.ArrowDropDown, "Choose skin tone")
                        }
                        DropdownMenu(expanded = toneMenu, onDismissRequest = { toneMenu = false }) {
                            for ((value, label) in tones) {
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 24.sp) },
                                    trailingIcon = {
                                        if (tone == value) Icon(Icons.Filled.Check, "Selected")
                                    },
                                    onClick = {
                                        tone = value
                                        vm.settings.set("chats.reaction_skin_tone", value)
                                        toneMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = { ctx ->
                        val themed = android.view.ContextThemeWrapper(ctx,
                            if (dark) android.R.style.Theme_Material_NoActionBar
                            else android.R.style.Theme_Material_Light_NoActionBar)
                        androidx.emoji2.emojipicker.EmojiPickerView(themed).apply {
                            emojiGridColumns = 8
                            setOnEmojiPickedListener { item ->
                                onPick(applySkinTone(item.emoji, tone))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** Apply one skin tone choice to hand and people emoji the user picks. */
private fun applySkinTone(emoji: String, tone: String): String {
    if (tone.isEmpty()) return emoji
    val tones = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")
    val existing = tones.firstOrNull { emoji.contains(it) }
    if (existing != null) return emoji.replace(existing, tone)
    val first = emoji.codePointAt(0)
    val base = Character.charCount(first)
    if (!supportsSkinTone(first)) return emoji
    var at = base
    if (emoji.length > at && emoji[at] == '\uFE0F') at++
    return emoji.substring(0, at) + tone + emoji.substring(at)
}

private fun supportsSkinTone(cp: Int): Boolean =
    cp == 0x261D || cp == 0x26F9 || cp == 0x270A || cp == 0x270B || cp == 0x270C || cp == 0x270D ||
        cp in 0x1F385..0x1F385 || cp in 0x1F3C2..0x1F3C4 || cp in 0x1F3C7..0x1F3C7 ||
        cp in 0x1F3CA..0x1F3CC || cp in 0x1F442..0x1F443 || cp in 0x1F446..0x1F450 ||
        cp in 0x1F466..0x1F487 || cp == 0x1F4AA || cp in 0x1F574..0x1F575 ||
        cp == 0x1F57A || cp == 0x1F590 || cp in 0x1F595..0x1F596 || cp in 0x1F645..0x1F64F ||
        cp in 0x1F6A3..0x1F6A3 || cp in 0x1F6B4..0x1F6B6 || cp == 0x1F6C0 ||
        cp in 0x1F90C..0x1F90F || cp in 0x1F918..0x1F91F || cp == 0x1F926 ||
        cp in 0x1F930..0x1F939 || cp in 0x1F93C..0x1F93E || cp == 0x1F977 ||
        cp in 0x1F9B5..0x1F9B6 || cp in 0x1F9B8..0x1F9B9 || cp == 0x1F9BB ||
        cp in 0x1F9CD..0x1F9CF || cp in 0x1F9D1..0x1F9DD || cp in 0x1FAC3..0x1FAC5 ||
        cp in 0x1FAF0..0x1FAF8

@Composable
private fun ForwardMessageDialog(vm: AppViewModel, msg: MessageEntity, chats: List<ChatEntity>,
                                 accounts: List<AccountEntity>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                chats.filter { it.id != msg.chatId }.forEach { target ->
                    val account = accounts.firstOrNull { it.id == target.accountId }
                    PanelListItem(
                        headlineContent = { Text(target.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(account?.jid ?: target.jid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Avatar(target.displayName, 32, photo = target.avatar, show = null) },
                        modifier = Modifier.clickable {
                            vm.forward(target, msg)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    PanelListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) },
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
    var encryption by remember { mutableStateOf(if (chat.encryption == "omemo") "default" else chat.encryption) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Avatar(chat.displayName, 72, photo = if (chat.isGroup) chat.avatar else contact?.avatar ?: chat.avatar,
                    show = if (chat.isGroup) null else contact?.presence)
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
                PanelListItem(headlineContent = { Text("Devices and keys") }, supportingContent = { Text("Fingerprints and verification") },
                    leadingContent = { Icon(Icons.Filled.Key, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.clickable { onDevices() })
            }
            val legacyEnabled = vm.settings.bool("encryption.legacy_omemo")
            val choices = if (chat.isGroup) {
                listOf("default" to "Default (OMEMO)", "off" to "Off")
            } else {
                listOf("default" to "Default (OMEMO)", "omemo2" to "OMEMO 2", "off" to "Off") +
                    if (vm.settings.bool("pgp.enabled")) listOf("pgp" to "OpenPGP") else emptyList()
            }
            Text("Encryption", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { (v, l) ->
                    FilterChip(selected = encryption == v, onClick = { encryption = v; vm.setEncryption(chat, v) }, label = { Text(l) })
                }
            }
            if (chat.isGroup) {
                Text("Default (OMEMO) uses legacy OMEMO for groups. OMEMO 2 group encryption is not implemented yet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!legacyEnabled && encryption != "off") {
                    Text("OMEMO (legacy) is turned off in Settings → Encryption. Choose Off or enable it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else if (!legacyEnabled && encryption == "default") {
                Text("Default (OMEMO) needs legacy OMEMO. Choose OMEMO 2 or Off, or enable legacy OMEMO in Settings → Encryption.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text("Default uses OMEMO (legacy) for compatibility. Choose OMEMO 2 for contacts that support it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            if (contact != null) {
                PanelListItem(headlineContent = { Text(if (contact.blocked) "Unblock" else "Block…") }, leadingContent = { Icon(Icons.Filled.Block, null) },
                    modifier = Modifier.clickable { vm.block(chat.accountId, chat.jid, !contact.blocked); onDismiss() })
                PanelListItem(headlineContent = { Text("Remove from contacts…") }, leadingContent = { Icon(Icons.Filled.PersonRemove, null) },
                    modifier = Modifier.clickable { vm.removeContact(chat.accountId, chat.jid); onDismiss() })
            }
            PanelListItem(headlineContent = { Text("Clear history…") }, leadingContent = { Icon(Icons.Filled.DeleteSweep, null) },
                modifier = Modifier.clickable { vm.clearHistory(chat); onDismiss() })
            if (chat.isGroup) {
                PanelListItem(headlineContent = { Text("Invite…") }, supportingContent = { Text("xmpp:${chat.jid}?join") },
                    leadingContent = { Icon(Icons.Filled.PersonAdd, null) }, modifier = Modifier.clickable { inviteOpen = true })
                PanelListItem(headlineContent = { Text("Leave group") }, leadingContent = { Icon(Icons.Filled.Logout, null) },
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
                    PanelListItem(headlineContent = { Text(o.nick + if (o.isSelf) " (you)" else "") },
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
