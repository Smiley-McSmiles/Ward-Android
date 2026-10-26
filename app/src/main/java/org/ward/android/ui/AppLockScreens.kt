package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ward.android.WardCore
import org.ward.android.util.AppLockStore

@Composable
fun AppUnlockScreen(context: android.content.Context, onUnlocked: () -> Unit) {
    val type = remember { AppLockStore.type(context) }
    var value by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var wiping by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(AppLockStore.lockoutRemaining(context) / 1000L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            remaining = AppLockStore.lockoutRemaining(context) / 1000L
            delay(500)
        }
    }

    fun attempt(secret: String) {
        if (wiping) return
        if (remaining > 0L) {
            error = "Try again in $remaining seconds"
            return
        }
        if (secret.isBlank()) {
            error = "Enter your ${if (type == AppLockStore.PIN) "PIN" else if (type == AppLockStore.PATTERN) "pattern" else "password"}"
            return
        }
        if (AppLockStore.verify(context, secret)) {
            AppLockStore.clearFailures(context)
            value = ""
            pattern = emptyList()
            error = ""
            onUnlocked()
            return
        }
        val failure = AppLockStore.recordFailure(context)
        remaining = AppLockStore.lockoutRemaining(context) / 1000L
        if (type == AppLockStore.PATTERN) pattern = emptyList()
        if (failure.wipe) {
            wiping = true
            error = "Too many failed attempts. Removing local keys and message history…"
            scope.launch {
                runCatching { WardCore.get().wipeEncryptionAndHistory() }
                AppLockStore.clearFailures(context)
                error = "Too many failed attempts. Local encryption keys and message history were deleted."
                wiping = false
            }
        } else {
            val limit = AppLockStore.maxAttempts(context)
            error = "Incorrect ${if (type == AppLockStore.PIN) "PIN" else if (type == AppLockStore.PATTERN) "pattern" else "password"}" +
                if (limit > 0) " · ${failure.attempts} of $limit attempts" else ""
        }
    }

    fun submit() {
        if (type == AppLockStore.PATTERN) {
            if (pattern.size < 4) { error = "Use at least four dots"; return }
            attempt(pattern.joinToString("-"))
        } else attempt(value)
    }

    BackHandler { /* Keep the app open on the lock screen. */ }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Avatar("Ward", 86, "#3584e4")
            Spacer(Modifier.height(18.dp))
            Text("Ward is locked", style = MaterialTheme.typography.headlineSmall)
            Text(when (type) {
                AppLockStore.PIN -> "Enter your PIN to continue"
                AppLockStore.PATTERN -> "Draw your pattern to unlock"
                else -> "Enter your password to continue"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            if (type == AppLockStore.PATTERN) {
                PatternPad(pattern, { pattern = it; error = "" }, enabled = remaining == 0L && !wiping,
                    onComplete = { seq ->
                        pattern = seq
                        if (seq.size >= 4) attempt(seq.joinToString("-")) else error = "Use at least four dots"
                    })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pattern = emptyList(); error = "" },
                        enabled = remaining == 0L && !wiping) { Text("Clear") }
                    Button(onClick = ::submit, enabled = pattern.size >= 4 && remaining == 0L && !wiping) { Text("Unlock") }
                }
            } else {
                OutlinedTextField(value, {
                    value = if (type == AppLockStore.PIN) it.filter { c -> c.isDigit() }.take(12) else it
                    error = ""
                }, label = { Text(if (type == AppLockStore.PIN) "PIN" else "Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (type == AppLockStore.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
                        imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    enabled = remaining == 0L && !wiping,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = ::submit, enabled = value.isNotBlank() && remaining == 0L && !wiping,
                    modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
            }
            if (remaining > 0L) {
                Spacer(Modifier.height(10.dp))
                Text("Locked out for $remaining seconds", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPage(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var currentType by remember { mutableStateOf(AppLockStore.type(context)) }
    var setupType by remember { mutableStateOf<String?>(null) }
    var maxAttempts by remember { mutableStateOf(AppLockStore.maxAttempts(context)) }
    var lockoutSeconds by remember { mutableStateOf(AppLockStore.lockoutSeconds(context)) }
    var lockDelaySeconds by remember { mutableStateOf(AppLockStore.lockDelaySeconds(context)) }

    BackHandler { onBack() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Security") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        }, colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 34.dp)) {
            PageIntro("security")
            SettingsSectionLabel("App lock")
            SettingsGroup {
                ListItem(
                    headlineContent = { Text("Current protection") },
                    supportingContent = {
                        Text(when (currentType) {
                            AppLockStore.PIN -> "PIN"
                            AppLockStore.PASSWORD -> "Password"
                            AppLockStore.PATTERN -> "Pattern"
                            else -> "Off — anyone who opens Ward can read local messages"
                        })
                    },
                    leadingContent = { Icon(if (currentType == AppLockStore.OFF) Icons.Filled.LockOpen else Icons.Filled.Lock, null) })
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Method", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AppLockStore.OFF to "Off",
                            AppLockStore.PIN to "PIN",
                            AppLockStore.PASSWORD to "Password",
                            AppLockStore.PATTERN to "Pattern"
                        ).forEach { (value, label) ->
                            FilterChip(selected = currentType == value, onClick = {
                                if (value == AppLockStore.OFF) {
                                    AppLockStore.clear(context)
                                    currentType = AppLockStore.OFF
                                } else {
                                    setupType = value
                                }
                            }, label = { Text(label) })
                        }
                    }
                    if (currentType != AppLockStore.OFF) {
                        Spacer(Modifier.height(8.dp))
                        Text("Choosing another method replaces the stored credential. Turning the app lock off removes it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SettingsSectionLabel("Failed attempts")
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Lockout pauses entry after a wrong attempt. At the attempt limit, Ward deletes local encryption keys and message history.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Never", 3 to "3 attempts", 5 to "5 attempts", 10 to "10 attempts").forEach { (value, label) ->
                            FilterChip(selected = maxAttempts == value, onClick = {
                                maxAttempts = value
                                AppLockStore.setMaxAttempts(context, value)
                                AppLockStore.clearFailures(context)
                            }, label = { Text(label) })
                        }
                    }
                    if (maxAttempts > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("After $maxAttempts failed attempts, local keys and message history are deleted.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            SettingsSectionLabel("Lockout")
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Wait before trying again after a failed unlock.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Off", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes").forEach { (value, label) ->
                            FilterChip(selected = lockoutSeconds == value, onClick = {
                                lockoutSeconds = value
                                AppLockStore.setLockoutSeconds(context, value)
                                AppLockStore.clearFailures(context)
                            }, label = { Text(label) })
                        }
                    }
                }
            }

            SettingsSectionLabel("Leaving the app")
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Briefly switching away can keep Ward unlocked. The lock screen appears after this delay while the app is in the background.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Immediately", 15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes").forEach { (value, label) ->
                            FilterChip(selected = lockDelaySeconds == value, onClick = {
                                lockDelaySeconds = value
                                AppLockStore.setLockDelaySeconds(context, value)
                            }, label = { Text(label) })
                        }
                    }
                }
            }
        }
    }

    val setup = setupType
    if (setup != null) {
        SetAppLockDialog(context, setup, onDismiss = { setupType = null }, onSaved = {
            currentType = setup
            setupType = null
        })
    }
}

@Composable
private fun SetAppLockDialog(context: android.content.Context, type: String,
                            onDismiss: () -> Unit, onSaved: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val isPattern = type == AppLockStore.PATTERN
    val title = when (type) {
        AppLockStore.PIN -> "Set a PIN"
        AppLockStore.PATTERN -> "Set a pattern"
        else -> "Set a password"
    }
    fun save() {
        if (isPattern) {
            if (pattern.size < 4) { error = "Use at least four dots"; return }
            AppLockStore.set(context, type, pattern.joinToString("-"))
            onSaved(); return
        }
        if (first.length < 4) { error = "Use at least four characters"; return }
        if (first != second) { error = "The entries do not match"; return }
        AppLockStore.set(context, type, first)
        onSaved()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isPattern) {
                    Text("Tap at least four dots.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    PatternPad(pattern, { pattern = it; error = "" })
                    if (pattern.isNotEmpty()) {
                        TextButton(onClick = { pattern = emptyList(); error = "" }) { Text("Clear pattern") }
                    }
                } else {
                    OutlinedTextField(first, {
                        first = if (type == AppLockStore.PIN) it.filter { c -> c.isDigit() }.take(12) else it
                        error = ""
                    }, label = { Text(if (type == AppLockStore.PIN) "New PIN" else "New password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (type == AppLockStore.PIN) KeyboardType.NumberPassword else KeyboardType.Password))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(second, {
                        second = if (type == AppLockStore.PIN) it.filter { c -> c.isDigit() }.take(12) else it
                        error = ""
                    }, label = { Text("Repeat") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (type == AppLockStore.PIN) KeyboardType.NumberPassword else KeyboardType.Password))
                    Text("At least four ${if (type == AppLockStore.PIN) "digits" else "characters"}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PatternPad(selected: List<Int>, onChange: (List<Int>) -> Unit,
               enabled: Boolean = true, onComplete: ((List<Int>) -> Unit)? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    var dragPoint by remember { mutableStateOf<Offset?>(null) }

    fun center(id: Int, w: Float, h: Float): Offset =
        Offset(((id % 3) * 2 + 1) * w / 6f, ((id / 3) * 2 + 1) * h / 6f)

    fun pegAt(pos: Offset, w: Float, h: Float): Int? {
        val radius = min(w, h) / 9f
        var best: Int? = null
        // The drawn pegs stay the same size; only the snap area is tighter so a drag has to
        // actually be near a peg to select it.
        var bestDistance = radius * 1.15f
        for (id in 0..8) {
            val c = center(id, w, h)
            val dx = pos.x - c.x
            val dy = pos.y - c.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d <= bestDistance) {
                bestDistance = d
                best = id
            }
        }
        return best
    }

    fun addPeg(list: MutableList<Int>, peg: Int) {
        if (peg in list) return
        val last = list.lastOrNull()
        if (last != null) {
            val r1 = last / 3; val c1 = last % 3
            val r2 = peg / 3; val c2 = peg % 3
            val mid = when {
                r1 == r2 && kotlin.math.abs(c1 - c2) == 2 -> last + (c2 - c1) / 2
                c1 == c2 && kotlin.math.abs(r1 - r2) == 2 -> last + ((r2 - r1) / 2) * 3
                kotlin.math.abs(r1 - r2) == 2 && kotlin.math.abs(c1 - c2) == 2 -> 4
                else -> null
            }
            if (mid != null && mid !in list) list.add(mid)
        }
        if (peg !in list) list.add(peg)
    }

    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var local = mutableListOf<Int>()
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            detectDragGestures(
                onDragStart = { pos ->
                    local = mutableListOf()
                    dragPoint = pos
                    pegAt(pos, w, h)?.let { addPeg(local, it) }
                    onChange(local.toList())
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragPoint = change.position
                    pegAt(change.position, w, h)?.let { peg ->
                        if (peg !in local) {
                            addPeg(local, peg)
                            onChange(local.toList())
                        }
                    }
                },
                onDragEnd = {
                    dragPoint = null
                    onComplete?.invoke(local.toList())
                },
                onDragCancel = { dragPoint = null }
            )
        }) {
        val w = size.width
        val h = size.height
        val radius = min(w, h) / 9f
        for (i in 1 until selected.size) {
            drawLine(primary, center(selected[i - 1], w, h), center(selected[i], w, h),
                strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
        }
        if (dragPoint != null && selected.isNotEmpty()) {
            drawLine(primary, center(selected.last(), w, h), dragPoint!!,
                strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
        }
        for (id in 0..8) {
            val c = center(id, w, h)
            val on = id in selected
            drawCircle(if (on) primary else surfaceVariant, radius, c)
            drawCircle(if (on) onPrimary else onSurfaceVariant, radius * 0.18f, c)
        }
    }
}
