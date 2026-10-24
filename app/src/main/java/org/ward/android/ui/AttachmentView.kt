package org.ward.android.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.ward.android.util.FileCrypto
import org.ward.android.xmpp.guessMime
import java.io.File

@Composable
fun AttachmentView(vm: AppViewModel, a: Map<String, Any>) {
    val url = a["url"]?.toString() ?: return
    val name = a["name"]?.toString()?.takeIf { it.isNotBlank() }
        ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#').ifBlank { "attachment" }
    val declaredMime = a["mime"]?.toString().orEmpty()
    val guessedMime = guessMime(name).takeUnless { it == "application/octet-stream" } ?: guessMime(url)
    val mime = when {
        declaredMime.isBlank() -> guessedMime
        declaredMime == "application/octet-stream" && guessedMime != "application/octet-stream" -> guessedMime
        else -> declaredMime
    }
    // The cache file needs a usable extension for MediaPlayer: peers sometimes send a display
    // name without one, or an encrypted upload named file.mp4.enc, while the MIME type still
    // says video/audio.
    val wantedExtension = extensionForMime(mime)
    val usableName = guessMime(name) != "application/octet-stream" && !name.endsWith(".enc", ignoreCase = true)
    val downloadName = if (usableName || wantedExtension.isBlank()) name else name + wantedExtension
    val size = (a["size"] as? Number)?.toLong() ?: 0L
    val encrypted = url.startsWith("aesgcm://")
    var file by remember(url) { mutableStateOf<File?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    val progressMap by vm.core.downloadProgress.collectAsState()
    val progress = progressMap[url]
    val context = LocalContext.current
    LaunchedEffect(url) {
        file = vm.core.localFile(url, downloadName, mime)
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
        mime.startsWith("video/") -> VideoAttachment(file, name, loading, failed, progress, size, encrypted)
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

@Composable
private fun VideoAttachment(file: File?, name: String, loading: Boolean, failed: Boolean,
                            progress: Float?, size: Long, encrypted: Boolean) {
    if (file == null) {
        Box(Modifier.widthIn(max = 290.dp).fillMaxWidth().height(150.dp)
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(6.dp))
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(170.dp))
                    else LinearProgressIndicator(Modifier.width(170.dp))
                    Text("Downloading… ${if (progress != null) "${(progress * 100).toInt()}%" else ""}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.Movie, null, Modifier.size(34.dp))
                    Text(if (failed) "Could not load video" else name, fontSize = 12.sp,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    var aspect by remember(file) { mutableFloatStateOf(16f / 9f) }
    LaunchedEffect(file) {
        aspect = withContext(Dispatchers.IO) { videoAspect(file) }
    }
    var fullscreen by remember { mutableStateOf(false) }
    var rotation by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoSurface(file = file, startPosition = position, autoPlay = playing,
                    rotation = rotation, aspect = aspect, fullscreen = true,
                    onState = { p, isPlaying -> position = p; playing = isPlaying },
                    onToggleFullscreen = { fullscreen = false },
                    onRotate = { rotation = (rotation + 90) % 360 })
            }
        }
    } else {
        VideoSurface(file = file, startPosition = position, autoPlay = playing,
            rotation = rotation, aspect = aspect, fullscreen = false,
            onState = { p, isPlaying -> position = p; playing = isPlaying },
            onToggleFullscreen = { fullscreen = true },
            onRotate = { rotation = (rotation + 90) % 360 })
    }

    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(listOfNotNull(
            size.takeIf { it > 0 }?.let { formatBytes(it) },
            if (encrypted) "encrypted" else null
        ).joinToString(" · "), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoSurface(file: File, startPosition: Int, autoPlay: Boolean, rotation: Int,
                         aspect: Float, fullscreen: Boolean,
                         onState: (Int, Boolean) -> Unit, onToggleFullscreen: () -> Unit,
                         onRotate: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(startPosition) }
    var scrubbing by remember { mutableStateOf(false) }
    val ratio = (if (rotation % 180 == 0) aspect else 1f / aspect).coerceIn(0.4f, 2.5f)

    DisposableEffect(file) {
        onDispose {
            player?.release()
            player = null
            surface?.release()
            surface = null
        }
    }
    LaunchedEffect(playing, scrubbing) {
        while (playing && !scrubbing) {
            val v = player ?: break
            val p = runCatching { v.currentPosition }.getOrDefault(position)
            val d = runCatching { v.duration }.getOrDefault(duration)
            if (d > 0) duration = d
            position = p
            onState(p, true)
            delay(500)
        }
    }

    val frame = if (fullscreen) Modifier.fillMaxSize()
    else Modifier.widthIn(max = 290.dp).fillMaxWidth().heightIn(max = 360.dp).aspectRatio(ratio)
    BoxWithConstraints(frame.clip(RoundedCornerShape(if (fullscreen) 0.dp else 8.dp)).background(Color.Black)) {
        // TextureView, not VideoView: SurfaceView transforms (the rotate control) are unreliable
        // on some devices. Lay the texture out in the video's own orientation, then rotate it.
        val turned = rotation % 180 != 0
        val videoModifier = if (turned) Modifier.requiredSize(maxHeight, maxWidth)
        else Modifier.fillMaxSize()
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            if (player != null) return
                            runCatching {
                                val s = Surface(st)
                                surface = s
                                val mp = MediaPlayer()
                                mp.setDataSource(file.absolutePath)
                                mp.setSurface(s)
                                mp.setOnPreparedListener { preparedPlayer ->
                                    prepared = true
                                    duration = preparedPlayer.duration
                                    if (startPosition > 0) preparedPlayer.seekTo(startPosition)
                                    if (autoPlay) {
                                        preparedPlayer.start()
                                        playing = true
                                        onState(startPosition, true)
                                    }
                                }
                                mp.setOnCompletionListener {
                                    playing = false
                                    position = duration
                                    onState(duration, false)
                                }
                                mp.setOnErrorListener { _, _, _ ->
                                    failed = true
                                    playing = false
                                    onState(position, false)
                                    true
                                }
                                mp.prepareAsync()
                                player = mp
                            }.onFailure { failed = true }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            player?.release()
                            player = null
                            surface?.release()
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            update = { it.rotation = rotation.toFloat() },
            modifier = videoModifier.align(Alignment.Center)
        )
        if (!prepared && !failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), strokeWidth = 2.dp)
        }
        if (failed) {
            Text("Could not play video", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { scrubbing = true; position = it.toInt() },
                onValueChangeFinished = {
                    if (prepared) player?.seekTo(position)
                    scrubbing = false
                    onState(position, playing)
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val v = player ?: return@IconButton
                    if (!prepared) return@IconButton
                    if (playing) {
                        v.pause(); playing = false
                    } else {
                        if (duration > 0 && position >= duration) {
                            v.seekTo(0); position = 0
                        }
                        v.start(); playing = true
                    }
                    onState(position, playing)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pause" else "Play", Modifier.size(21.dp), tint = Color.White)
                }
                IconButton(onClick = {
                    val v = player ?: return@IconButton
                    if (!prepared) return@IconButton
                    v.pause(); v.seekTo(0)
                    playing = false; position = 0
                    onState(0, false)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Stop, "Stop", Modifier.size(19.dp), tint = Color.White)
                }
                Text("${formatDuration(position)} / ${formatDuration(duration)}", color = Color.White,
                    fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRotate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.RotateRight, "Rotate video", Modifier.size(19.dp), tint = Color.White)
                }
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(32.dp)) {
                    Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        if (fullscreen) "Minimize" else "Maximize", Modifier.size(21.dp), tint = Color.White)
                }
            }
        }
    }
}

private fun videoAspect(file: File): Float = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 16f / 9f
    } finally {
        retriever.release()
    }
}.getOrDefault(16f / 9f)

private fun formatDuration(ms: Int): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun extensionForMime(raw: String): String = when (raw.substringBefore(';').trim().lowercase()) {
    "video/mp4" -> ".mp4"; "video/x-matroska" -> ".mkv"; "video/webm" -> ".webm"
    "video/quicktime" -> ".mov"; "video/x-msvideo" -> ".avi"; "video/3gpp" -> ".3gp"
    "video/3gpp2" -> ".3g2"; "video/mpeg" -> ".mpeg"; "video/mp2t" -> ".ts"; "video/ogg" -> ".ogv"
    "audio/mp4" -> ".m4a"; "audio/mpeg" -> ".mp3"; "audio/ogg" -> ".ogg"; "audio/wav" -> ".wav"
    "audio/flac" -> ".flac"; "audio/amr" -> ".amr"; "audio/webm" -> ".weba"
    "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"; "image/webp" -> ".webp"
    else -> ""
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
