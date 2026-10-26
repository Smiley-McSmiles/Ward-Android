package org.ward.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import org.ward.android.data.ChatEntity
import org.ward.android.data.ContactEntity
import org.ward.android.util.Colors
import org.ward.android.util.TimeUtil

/** Set by [WardTheme] so the avatar-shape preference applies everywhere. */
val LocalAvatarShapeRounded = compositionLocalOf { false }

/** The elevated purple-grey panel used throughout the app in dark mode. */
@Composable
fun wardPanelColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
    MaterialTheme.colorScheme.surfaceContainer
} else {
    MaterialTheme.colorScheme.surfaceContainerLow
}

/** Android Settings-style pastel icon tiles. */
object WardIconColors {
    val blue = Color(0xFFA8C7FA)
    val cyan = Color(0xFF9DDDEB)
    val green = Color(0xFFA6E6A0)
    val yellow = Color(0xFFF2D06B)
    val orange = Color(0xFFF4BE86)
    val pink = Color(0xFFF2A7C3)
    val purple = Color(0xFFC9B6F5)
    val red = Color(0xFFF2A6A0)
    val teal = Color(0xFF9DE0D2)
    val grey = Color(0xFFCAC4D0)
}

@Composable
fun SettingsIconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((size * 0.54f).dp), tint = Color(0xFF1C1B1F))
    }
}

/** Dark mode uses a background-coloured gap between rows, matching Android settings panels. */
@Composable
fun wardDividerColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
    MaterialTheme.colorScheme.background
} else {
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
}

@Composable
fun WardBackButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = wardPanelColor(),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

@Composable
fun Avatar(text: String, size: Int = 44, color: String = "", modifier: Modifier = Modifier, show: String? = null,
           photo: String = "") {
    val roundedPreference = LocalAvatarShapeRounded.current
    val shape = if (size > 60) RoundedCornerShape(size.dp / 3)
        else if (roundedPreference) RoundedCornerShape((size * 0.3f).dp) else CircleShape
    val fallback: @Composable () -> Unit = {
        Text(Colors.initials(text), color = Color.White, fontSize = (size * 0.38).sp, fontWeight = FontWeight.SemiBold)
    }
    Box(modifier = modifier.size(size.dp).clip(shape).background(parseColor(color.ifBlank { Colors.forJid(text) })),
        contentAlignment = Alignment.Center) {
        if (photo.isNotBlank()) {
            SubcomposeAsyncImage(model = photo, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                loading = { },
                error = { fallback() })
        } else fallback()
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

/**
 * A rounded Material 3 surface that groups related preferences. Section titles live outside the
 * surface, so every page has one consistent background instead of alternating list bands.
 */
@Composable
fun SettingsGroup(
    title: String? = null,
    supporting: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
        if (title != null) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = wardPanelColor(),
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun SettingsDivider(startIndent: Int = 0) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent.dp),
        thickness = 1.dp,
        color = wardDividerColor()
    )
}

@Composable
fun WardSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun ChatRow(
    chat: ChatEntity,
    typing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
    compact: Boolean = false,
    showAvatar: Boolean = true,
    showTimestamp: Boolean = true,
    photo: String = chat.avatar
) {
    val context = LocalContext.current
    val twelveHour = TimeUtil.twelveHour(context, LocalTimeFormat.current)
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = wardPanelColor(), tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 1.dp else 3.dp)) {
        ListItem(
            modifier = Modifier,
            leadingContent = if (showAvatar) ({ Avatar(chat.displayName, 44, photo = photo) }) else null,
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(chat.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f), fontSize = 16.sp)
                    if (chat.pinned) Icon(Icons.Filled.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showTimestamp) Text(TimeUtil.listStamp(chat.lastActivity, LocalTimeSeconds.current, twelveHour),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp))
                }
            },
            supportingContent = if (showPreview) ({
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
            }) else null,
            trailingContent = null
        )
    }
}

@Composable
fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = wardPanelColor(),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        ListItem(
            leadingContent = { Avatar(contact.name, 40, photo = contact.avatar, show = contact.presence) },
            headlineContent = { Text(contact.name, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(if (contact.presence == "offline") contact.jid else contact.status.ifBlank {
                    when (contact.presence) { "available", "chat" -> "Available"; "away" -> "Away"; "dnd" -> "Busy"; "xa" -> "Away for a while"; else -> "Offline" }
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        )
    }
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
        colors = CardDefaults.cardColors(containerColor = wardPanelColor())) {
        Column(content = content)
    }
}
