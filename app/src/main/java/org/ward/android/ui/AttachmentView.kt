package org.ward.android.ui

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import org.ward.android.util.FileCrypto
import java.io.File

@Composable
fun AttachmentView(vm: AppViewModel, a: Map<String, Any>) {
    val url = a["url"]?.toString() ?: return
    val name = a["name"]?.toString() ?: url.substringAfterLast('/')
    val mime = a["mime"]?.toString() ?: org.ward.android.xmpp.guessMime(name)
    val size = (a["size"] as? Number)?.toLong() ?: 0L
    val encrypted = url.startsWith("aesgcm://")
    var file by remember(url) { mutableStateOf<File?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    val progressMap by vm.core.downloadProgress.collectAsState()
    val progress = progressMap[url]
    val context = LocalContext.current
    LaunchedEffect(url) {
        file = vm.core.localFile(url, name, mime)
        failed = file == null
        loading = false
    }
    when {
        mime.startsWith("image/") -> Box(Modifier.widthIn(max = 290.dp).heightIn(max = 320.dp), contentAlignment = Alignment.Center) {
            val image = file
            if (image != null) AsyncImage(model = image, contentDescription = name,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    openWithDefaultApp(context, image, mime)
                }, contentScale = ContentScale.Fit)
            else if (loading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(6.dp))
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(170.dp))
                else LinearProgressIndicator(Modifier.width(170.dp))
                Text("Downloading… ${if (progress != null) "${(progress * 100).toInt()}%" else ""}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else Text(if (failed) "Could not load image" else name, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        mime.startsWith("audio/") -> AudioAttachment(file, name, loading, failed, progress, mime, vm, url)
        else -> {
            Row(Modifier.clickable {
                val f = file ?: return@clickable
                openWithDefaultApp(context, f, mime)
            }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mime.startsWith("video/")) Icons.Filled.Movie else Icons.Filled.InsertDriveFile, null, Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    if (loading) {
                        if (progress != null) LinearProgressIndicator(progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text("Downloading… ${if (progress != null) "${(progress * 100).toInt()}%" else ""}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(if (failed) "Download failed" else formatBytes(size) +
                            if (encrypted) " · encrypted" else "", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun openWithDefaultApp(context: android.content.Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "org.ward.android.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri,
            mime.ifBlank { "application/octet-stream" }).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
        }
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AudioAttachment(file: File?, name: String, loading: Boolean, failed: Boolean,
                            progress: Float?, mime: String, vm: AppViewModel, url: String) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { player?.release(); player = null } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        FilledIconButton(onClick = {
            if (file == null) return@FilledIconButton
            if (playing) { player?.pause(); playing = false }
            else {
                if (player == null) {
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath); prepare()
                        duration = this.duration
                        setOnCompletionListener { playing = false }
                    }
                }
                player?.start(); playing = true
            }
        }, enabled = file != null) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(if (loading) "Voice message…" else name, fontSize = 13.sp)
            if (loading) {
                if (progress != null) LinearProgressIndicator(progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("Downloading… ${if (progress != null) "${(progress * 100).toInt()}%" else ""}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(if (failed) "Download failed" else if (duration > 0) "${duration / 1000}s · encrypted audio" else "Voice message",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { file?.let { openWithDefaultApp(context, it, mime) } }, enabled = file != null) {
            Icon(Icons.Filled.OpenInNew, "Open audio with another app")
        }
    }
}

fun formatBytes(size: Long): String = when {
    size > 1_000_000 -> "%.1f MB".format(size / 1_000_000.0)
    size > 1_000 -> "%.0f kB".format(size / 1_000.0)
    else -> "$size B"
}
