package org.ward.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ward.android.R
import org.ward.android.util.JidUtil
import org.ward.android.xmpp.Registration

private enum class WelcomePage { START, SIGNIN, CREATE, INVITE }

@Composable
fun WelcomeScreen(vm: AppViewModel, onBack: (() -> Unit)? = null) {
    var page by remember { mutableStateOf(WelcomePage.START) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            WelcomePage.START -> StartPage(onSignIn = { page = WelcomePage.SIGNIN }, onCreate = { page = WelcomePage.CREATE },
                onInvite = { page = WelcomePage.INVITE }, onBack = onBack)
            WelcomePage.SIGNIN -> SignInPage(vm, onBack = { page = WelcomePage.START })
            WelcomePage.CREATE -> CreateAccountPage(vm, onBack = { page = WelcomePage.START })
            WelcomePage.INVITE -> InvitePage(vm, onBack = { page = WelcomePage.START })
        }
    }
}

@Composable
private fun StartPage(onSignIn: () -> Unit, onCreate: () -> Unit, onInvite: () -> Unit, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (onBack != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onBack) { Icon(Icons.Filled.Close, null); Text("Cancel") }
        }
        Image(painterResource(R.drawable.ward_foreground), "Ward", Modifier.size(132.dp))
        Spacer(Modifier.height(20.dp))
        Text("Welcome to Ward", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text("Private chats and groups on the open XMPP network. Your messages are end-to-end encrypted, and you can use any server, or your own.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(30.dp))
        Button(onClick = onSignIn, Modifier.widthIn(max = 340.dp).fillMaxWidth().height(50.dp),
            shape = CircleShape) { Text("Sign in", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onCreate, Modifier.widthIn(max = 340.dp).fillMaxWidth().height(50.dp),
            shape = CircleShape) { Text("Create an account", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onInvite) { Text("I have an invite link", style = MaterialTheme.typography.titleSmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInPage(vm: AppViewModel, onBack: () -> Unit) {
    var jid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf("auto") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val accounts by vm.accounts.collectAsState()
    val states by vm.states.collectAsState()
    val latest = accounts.lastOrNull()
    val state = latest?.let { states[it.id] }
    LaunchedEffect(state?.state) {
        if (state?.state == "online") busy = false
        if (state?.state == "error") { error = state.error; busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Sign in") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 460.dp)) {
                Text("Your address looks like an email address: name@server",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(jid, { jid = it }, label = { Text("Address") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it })
                    Text("Remember password")
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    Text("Connection settings")
                }
                if (advanced) {
                    OutlinedTextField(host, { host = it }, label = { Text("Server address (host)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (0 for automatic)") },
                        singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Connection encryption", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Automatic", "starttls" to "STARTTLS").forEach { (v, l) ->
                            FilterChip(selected = tls == v, onClick = { tls = v }, label = { Text(l) })
                        }
                    }
                    Text("Only needed for unusual servers", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(18.dp))
                if (state?.state == "connecting" || busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state?.step?.ifBlank { "Connecting…" } ?: "Connecting…")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                }
                Button(onClick = {
                    val b = JidUtil.bare(jid)
                    when {
                        !JidUtil.valid(b) -> error = "Enter your full address, like alex@example.org"
                        password.isBlank() -> error = "Enter your password"
                        else -> {
                            error = ""; busy = true
                            val opts = mutableMapOf<String, String>()
                            if (host.isNotBlank()) opts["host"] = host.trim()
                            if (port.isNotBlank() && port.toIntOrNull() != 0) opts["port"] = port
                            if (tls != "auto") opts["tls"] = tls
                            if (!remember) opts["store_password"] = "never"
                            vm.addAccount(b, password, remember, opts)
                        }
                    }
                }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = CircleShape) {
                    Text("Sign in")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateAccountPage(vm: AppViewModel, onBack: () -> Unit) {
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Create an account") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
Column(Modifier.widthIn(max = 460.dp)) {
                Text("Like email, you can write to people on every other server. Pick one you trust; you can move later.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(domain, { domain = it.trim() }, label = { Text("Server, like example.org") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(username, { username = it.trim() }, label = { Text("User name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(18.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    if (domain.isBlank() || username.isBlank() || password.length < 6) {
                        error = "Enter a server, a user name and at least 6 characters of password"; return@Button
                    }
                    busy = true; error = ""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Registration.createAccount(domain, username, password) }
                            vm.addAccount("$username@$domain", password, true)
                            busy = false
                        } catch (e: Exception) {
                            error = e.message ?: "Could not create the account"; busy = false
                        }
                    }
                }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = CircleShape) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create account")
                }
                Spacer(Modifier.height(12.dp))
                Text("The server may ask for a CAPTCHA or extra fields; Ward shows its registration form.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvitePage(vm: AppViewModel, onBack: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Invite link") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
            Text("Paste the invite link you received. It lets you create an account on that server.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(link, { link = it }, label = { Text("Invite link") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val l = link.trim()
                if (!l.contains("xmpp:") && !l.contains("https://")) { message = "That doesn't look like an invite link"; return@Button }
                message = "Invite links that create an account are opened in your browser. For an existing account, sign in with its address."
            }, Modifier.fillMaxWidth()) { Text("Open invite") }
            if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message) }
        }
    }
}
