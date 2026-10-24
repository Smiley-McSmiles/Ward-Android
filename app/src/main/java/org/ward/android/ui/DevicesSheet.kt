package org.ward.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ward.android.data.AccountEntity
import org.ward.android.data.DeviceEntity
import org.ward.android.util.AppLockStore
import org.ward.android.xmpp.OmemoBackup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(vm: AppViewModel, accountId: Long, jid: String, own: Boolean = false, onDismiss: () -> Unit) {
    val devices by vm.core.dao.devices(accountId, jid).collectAsState(initial = emptyList())
    var forget by remember { mutableStateOf<DeviceEntity?>(null) }
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var dialogKind by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var pendingBlob by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExportPass by remember { mutableStateOf("") }
    var pendingImportTrust by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var cleanConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(jid) { vm.loadDevices(accountId, jid) }
    LaunchedEffect(accountId) { account = vm.core.account(accountId) }

    fun finishFile(uri: android.net.Uri?, kind: String) {
        AppLockStore.endExternalActivity()
        if (uri == null) return
        val pass = pendingExportPass
        pendingExportPass = ""
        if (pass.isBlank()) return
        busy = true
        message = ""
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val blob = if (kind == "device") {
                        val acc = account ?: error("Account is not loaded")
                        OmemoBackup.exportDevice(vm.core, acc, pass)
                    } else {
                        OmemoBackup.exportTrust(vm.core, pass)
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                        ?: error("Could not open the selected file")
                }
            }
            busy = false
            message = result.fold({ "Exported" }, { it.message ?: "Could not export" })
        }
    }

    val exportDevice = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        finishFile(it, "device")
    }
    val exportTrust = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        finishFile(it, "trust")
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLockStore.endExternalActivity()
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = ""
        scope.launch {
            val blob = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read the selected file")
                }
            }.getOrNull()
            busy = false
            if (blob == null) {
                message = "Could not read the selected file"
            } else {
                pendingBlob = blob
                dialogKind = if (pendingImportTrust) "import-trust" else "import-device"
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(if (own) "Your devices and encryption keys" else "Devices and keys", style = MaterialTheme.typography.titleLarge)
            Text("Fingerprints are shown in the format Conversations, Gajim and Ward use.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (devices.isEmpty()) {
                Text("No OMEMO devices have been published for this address yet.", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                devices.forEach { d -> DeviceRow(vm, accountId, jid, d, onForget = { forget = d }) }
                if (own && account != null) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Backup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Passphrase-protected files compatible with Ward for GTK. Android-to-Android restores also keep sessions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp))
                    ListItem(
                        headlineContent = { Text("Export this device…") },
                        supportingContent = { Text("OMEMO identity, pre-keys and sessions") },
                        leadingContent = { Icon(Icons.Filled.Download, null) },
                        modifier = Modifier.clickable { dialogKind = "export-device"; passphrase = "" })
                    ListItem(
                        headlineContent = { Text("Import a device…") },
                        supportingContent = { Text("Restore a device backup; use one copy at a time") },
                        leadingContent = { Icon(Icons.Filled.Upload, null) },
                        modifier = Modifier.clickable {
                            pendingImportTrust = false
                            AppLockStore.beginExternalActivity()
                            importFile.launch(arrayOf("application/octet-stream", "*/*"))
                        })
                    ListItem(
                        headlineContent = { Text("Export trust decisions…") },
                        supportingContent = { Text("Verified and distrusted keys for all accounts") },
                        leadingContent = { Icon(Icons.Filled.Download, null) },
                        modifier = Modifier.clickable { dialogKind = "export-trust"; passphrase = "" })
                    ListItem(
                        headlineContent = { Text("Import trust decisions…") },
                        supportingContent = { Text("Restore verification choices") },
                        leadingContent = { Icon(Icons.Filled.Upload, null) },
                        modifier = Modifier.clickable {
                            pendingImportTrust = true
                            AppLockStore.beginExternalActivity()
                            importFile.launch(arrayOf("application/octet-stream", "*/*"))
                        })
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Clean up old legacy devices…") },
                        supportingContent = { Text("Keep only this device in your published OMEMO 0.3 device list") },
                        leadingContent = { Icon(Icons.Filled.DeleteSweep, null) },
                        modifier = Modifier.clickable { cleanConfirm = true })
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (cleanConfirm) {
        AlertDialog(onDismissRequest = { cleanConfirm = false },
            title = { Text("Clean up old legacy devices?") },
            text = { Text("This publishes a legacy OMEMO (0.3) device list containing only this phone. " +
                "Old installs and resets will stop receiving new messages. Contacts may need to reopen the chat " +
                "so they fetch the new list. OMEMO 2 devices are not affected.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    cleanConfirm = false
                    busy = true
                    message = ""
                    scope.launch {
                        val result = runCatching {
                            vm.core.session(accountId)?.cleanUpLegacyDevices() ?: "Not connected"
                        }.getOrElse { it.message ?: "Could not clean up devices" }
                        busy = false
                        message = result
                        vm.loadDevices(accountId, jid)
                    }
                }) { Text("Clean up") }
            },
            dismissButton = { TextButton(onClick = { cleanConfirm = false }) { Text("Cancel") } })
    }
    if (forget != null) {
        val d = forget!!
        AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget device ${d.deviceId}?") },
            text = { Text("This removes the stored identity, trust and encrypted session for this device. It does not affect the device itself; it will be added again if it sends a new key exchange.") },
            confirmButton = { TextButton(onClick = {
                vm.forgetDevice(accountId, jid, d.deviceId, d.protocol); forget = null
            }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
    }

    val kind = dialogKind
    if (kind != null) {
        AlertDialog(
            onDismissRequest = { dialogKind = null; pendingBlob = null },
            title = { Text(if (kind.startsWith("export")) "Choose a passphrase" else "Enter the file's passphrase") },
            text = {
                Column {
                    Text(if (kind.startsWith("export")) "You will need this passphrase to restore the file."
                        else "Enter the passphrase used when the file was exported.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = passphrase.isNotBlank() && !busy, onClick = {
                    val pass = passphrase
                    passphrase = ""
                    val current = kind
                    dialogKind = null
                    if (current == null) return@TextButton
                    if (current.startsWith("export")) {
                        pendingExportPass = pass
                        AppLockStore.beginExternalActivity()
                        if (current == "export-device") exportDevice.launch(OmemoBackup.defaultDeviceFileName())
                        else exportTrust.launch(OmemoBackup.defaultTrustFileName())
                    } else {
                        val blob = pendingBlob
                        pendingBlob = null
                        val acc = account
                        if (blob == null || (current == "import-device" && acc == null)) {
                            message = "The selected file is not available"
                            return@TextButton
                        }
                        busy = true
                        message = ""
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    if (current == "import-device") {
                                        OmemoBackup.importDevice(vm.core, acc!!, blob, pass)
                                    } else {
                                        "Imported ${OmemoBackup.importTrust(vm.core, blob, pass)} trust decisions"
                                    }
                                }
                            }
                            busy = false
                            message = result.getOrElse { it.message ?: "Could not import" }
                            vm.loadDevices(accountId, jid)
                        }
                    }
                }) { Text(if (kind.startsWith("export")) "Export" else "Import") }
            },
            dismissButton = { TextButton(onClick = { dialogKind = null; pendingBlob = null }) { Text("Cancel") } })
    }
}

@Composable
private fun DeviceRow(vm: AppViewModel, accountId: Long, jid: String, d: DeviceEntity, onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.thisDevice) "This device" else "Device ${d.deviceId}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(d.trust.replaceFirstChar { it.uppercase() }) })
            }
            if (d.label.isNotBlank()) Text(d.label, style = MaterialTheme.typography.bodySmall)
            Text(if (d.protocol == "omemo2") "OMEMO 2" else "Legacy OMEMO", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(formatFingerprint(d.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.trust != "verified") Button(onClick = {
                    vm.trustDevice(accountId, jid, d.deviceId, d.fingerprint, true, d.protocol)
                }) { Text("Trust") }
                if (d.trust != "distrusted") OutlinedButton(onClick = {
                    vm.trustDevice(accountId, jid, d.deviceId, d.fingerprint, false, d.protocol)
                }) { Text("Distrust") }
                if (!d.own) OutlinedButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

fun formatFingerprint(fp: String): String {
    val clean = fp.replace(" ", "").replace(":", "")
    return clean.chunked(8).joinToString(" ")
}
