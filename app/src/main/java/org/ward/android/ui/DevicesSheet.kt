package org.ward.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ward.android.data.DeviceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(vm: AppViewModel, accountId: Long, jid: String, own: Boolean = false, onDismiss: () -> Unit) {
    val devices by vm.core.dao.devices(accountId, jid).collectAsState(initial = emptyList())
    var forget by remember { mutableStateOf<DeviceEntity?>(null) }
    LaunchedEffect(jid) { vm.loadDevices(accountId, jid) }
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
            }
        }
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
