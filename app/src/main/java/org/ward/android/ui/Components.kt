package org.ward.android.ui

import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ward.android.data.ChatEntity
import org.ward.android.data.ContactEntity
import org.ward.android.util.Colors
import org.ward.android.util.TimeUtil

@Composable
fun Avatar(text: String, size: Int = 44, color: String = "", modifier: Modifier = Modifier, show: String? = null,
           photo: String = "") {
    val shape = if (size > 60) RoundedCornerShape(size.dp / 3) else CircleShape
    Box(modifier = modifier.size(size.dp).clip(shape).background(parseColor(color.ifBlank { Colors.forJid(text) })),
        contentAlignment = Alignment.Center) {
        if (photo.isNotBlank()) {
            AsyncImage(model = photo, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop)
        } else {
            Text(Colors.initials(text), color = Color.White, fontSize = (size * 0.38).sp, fontWeight = FontWeight.SemiBold)
        }
        if (show != null && show != "offline") {
            Box(Modifier.align(Alignment.BottomEnd).size((size * 0.24).dp).clip(CircleShape)
                .background(presenceColor(show)).padding(0.dp))
        }
    }
}

fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) { Color(0xFF3584E4) }

fun presenceColor(show: String): Color = when (show) {
    "available", "chat" -> Color(0xFF2EC27E)
    "away" -> Color(0xFFF5C211)
    "dnd" -> Color(0xFFE01B24)
    "xa" -> Color(0xFFC88800)
    else -> Color(0xFF9A9996)
}

@Composable
fun PresenceDot(show: String, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(presenceColor(show)))
}

@Composable
fun ChatRow(chat: ChatEntity, typing: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
    ListItem(
        modifier = Modifier,
        leadingContent = { Avatar(chat.displayName, 44, chat.avatar.ifBlank { "" }, show = null) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), fontSize = 16.sp)
                if (chat.pinned) Icon(Icons.Filled.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(TimeUtil.listStamp(chat.lastActivity), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp))
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = when {
                    typing.isNotBlank() -> "$typing is typing…"
                    chat.preview.isNotBlank() -> if (chat.isGroup) "${chat.previewSender}: ${chat.preview}" else chat.preview
                    else -> "No messages yet"
                }
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    fontSize = 14.sp, color = if (typing.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (chat.unread > 0) FontWeight.Medium else FontWeight.Normal)
                if (chat.mutedUntil == -1L || chat.mutedUntil > System.currentTimeMillis()) Icon(Icons.Filled.NotificationsOff, null, Modifier.size(15.dp))
                if (chat.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge(containerColor = if (chat.mentions > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                        Text(if (chat.unread > 99) "99+" else chat.unread.toString(), color = Color.White)
                    }
                }
            }
        },
        trailingContent = null
    )
    }
}

@Composable
fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(contact.name, 40, photo = contact.avatar, show = contact.presence) },
        headlineContent = { Text(contact.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(if (contact.presence == "offline") contact.jid else contact.status.ifBlank {
                when (contact.presence) { "available", "chat" -> "Available"; "away" -> "Away"; "dnd" -> "Busy"; "xa" -> "Away for a while"; else -> "Offline" }
            }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(content = content)
    }
}
