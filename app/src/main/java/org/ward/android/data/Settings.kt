package org.ward.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SettingType { BOOL, CHOICE, INT, TEXT, LIST, TIME_RANGE }
data class SettingChoice(val value: Any, val label: String)
data class SettingSpec(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val page: String,
    val group: String,
    val type: SettingType,
    val default: Any,
    val choices: List<SettingChoice> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int = 1,
    val unit: String = "",
    val advanced: Boolean = false
)

/**
 * Preferences are grouped by where the user would look for them, not by where they appear in the
 * GTK desktop app. Controls that only made sense on the desktop (tray icon, hover timestamps,
 * custom fonts, URI-handler registration) are intentionally absent: on Android they did nothing,
 * and dead switches make the rest of the settings feel unreliable.
 */
object SettingsSchema {
    val pages = listOf(
        "general" to "General",
        "appearance" to "Appearance",
        "chats" to "Chats",
        "calls" to "Calls",
        "attachments" to "Attachments",
        "notifications" to "Notifications",
        "encryption" to "Encryption",
        "privacy" to "Privacy",
        "security" to "Security",
        "accounts" to "Accounts",
        "storage" to "Storage",
        "advanced" to "Advanced"
    )

    private fun b(key: String, title: String, sub: String, page: String, group: String, def: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.BOOL, def)
    private fun c(key: String, title: String, sub: String, page: String, group: String, def: Any, vararg choices: Pair<Any, String>) =
        SettingSpec(key, title, sub, page, group, SettingType.CHOICE, def, choices.map { SettingChoice(it.first, it.second) })
    private fun i(key: String, title: String, sub: String, page: String, group: String, def: Int, min: Int, max: Int, step: Int = 1, unit: String = "") =
        SettingSpec(key, title, sub, page, group, SettingType.INT, def, min = min, max = max, step = step, unit = unit)
    private fun t(key: String, title: String, sub: String, page: String, group: String, def: String = "") =
        SettingSpec(key, title, sub, page, group, SettingType.TEXT, def)
    private fun l(key: String, title: String, sub: String, page: String, group: String, def: List<String> = emptyList()) =
        SettingSpec(key, title, sub, page, group, SettingType.LIST, def)

    val defaultReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")
    private val defaultHighlightWords = emptyList<String>()

    val specs: List<SettingSpec> = listOf(
        // ------------------------------------------------------------- General
        b("general.background", "Run in the background", "Stay connected when Ward is not on screen so messages and calls still arrive", "general", "Background", true),
        b("general.autostart", "Start after restarting the phone", "Reconnect automatically after Android finishes booting", "general", "Background"),
        b("general.update_check", "Check for updates", "Ask GitHub once a day for a newer Ward release", "general", "Updates", true),

        // ---------------------------------------------------------- Appearance
        c("appearance.style", "Theme", "Follow the system, or always use light or dark", "appearance", "Colours", "system",
            "system" to "Follow system", "light" to "Light", "dark" to "Dark"),
        c("appearance.accent", "Accent colour", "Used for buttons, switches, links and selected items", "appearance", "Colours", "blue",
            "blue" to "Blue", "teal" to "Teal", "green" to "Green", "yellow" to "Yellow", "orange" to "Orange",
            "red" to "Red", "pink" to "Pink", "purple" to "Purple", "slate" to "Slate"),
        i("appearance.text_scale", "Text size", "Scales every screen in Ward", "appearance", "Text", 100, 80, 200, 10, "%"),
        b("appearance.large_emoji", "Large emoji in emoji-only messages", "", "appearance", "Text", true),
        c("appearance.layout", "Message layout", "How conversation bubbles are drawn", "appearance", "Messages", "cozy",
            "bubbles" to "Bubbles", "cozy" to "Cozy: names and avatars", "compact" to "Compact: like IRC"),
        b("appearance.message_animations", "Animate new messages", "New messages slide into the conversation", "appearance", "Messages", true),
        c("appearance.avatars", "Avatars in chats", "", "appearance", "Messages", "always",
            "always" to "Always", "groups" to "Groups only", "never" to "Never"),
        c("appearance.avatar_shape", "Avatar shape", "", "appearance", "Messages", "circle",
            "circle" to "Circle", "rounded" to "Rounded square"),
        c("appearance.time_format", "Time format", "", "appearance", "Messages", "system",
            "system" to "Follow system", "12" to "12-hour", "24" to "24-hour"),
        b("appearance.time_seconds", "Show seconds in message times", "", "appearance", "Messages"),
        c("appearance.list_density", "Chat list density", "", "appearance", "Chat list", "comfortable",
            "comfortable" to "Comfortable", "compact" to "Compact"),
        b("appearance.list_previews", "Message previews", "Show the last message under each chat", "appearance", "Chat list", true),

        // --------------------------------------------------------------- Chats
        c("chats.send_with", "Send with", "Choose whether Enter sends or starts a new line", "chats", "Writing", "enter",
            "enter" to "Enter sends", "newline" to "Enter adds a new line"),
        l("chats.quick_reactions", "Quick reactions", "The emoji shown in the reaction bar, one per line", "chats", "Messages", defaultReactions),
        c("chats.reaction_skin_tone", "Reaction skin tone", "Used for hand and people reactions", "chats", "Messages", "",
            "" to "Default", "\uD83C\uDFFB" to "Light", "\uD83C\uDFFC" to "Medium light",
            "\uD83C\uDFFD" to "Medium", "\uD83C\uDFFE" to "Medium dark", "\uD83C\uDFFF" to "Dark"),
        b("chats.confirm_delete", "Confirm “Delete for everyone”", "Ask before retracting a message for the other person", "chats", "Messages", true),
        b("chats.send_typing", "Send typing indicators", "", "chats", "Sharing", true),
        b("chats.show_typing", "Show typing indicators", "", "chats", "Sharing", true),
        b("chats.send_markers", "Send read markers", "", "chats", "Sharing", true),
        b("chats.send_receipts", "Send delivery receipts", "", "chats", "Sharing", true),

        // --------------------------------------------------------------- Calls
        b("calls.enabled", "Audio and video calls", "Contacts can call you, and you them, on this device", "calls", "Calls", true),
        i("calls.speaker_gain", "Speaker volume", "Applied to the other person's audio during calls", "calls", "Audio", 100, 50, 200, 5, "%"),
        b("calls.discover", "Discover STUN/TURN from the server", "Use XEP-0215 external service discovery for call relay servers", "calls", "Network", true),
        t("calls.stun", "STUN servers", "Space-separated stun:host:port addresses", "calls", "Network"),
        t("calls.turn", "TURN servers", "Space-separated turn:host:port addresses; add ?transport=tcp for TCP", "calls", "Network"),
        t("calls.turn_user", "TURN username", "", "calls", "Network"),
        t("calls.turn_password", "TURN password", "", "calls", "Network"),
        t("calls.turn_secret", "TURN shared secret", "For time-limited credentials, as used by coturn and eturnal", "calls", "Network"),
        i("calls.turn_ttl", "TURN credential lifetime", "Seconds; used with the shared secret", "calls", "Network", 86400, 300, 604800, 3600, "seconds"),
        b("calls.relay_only", "Always relay calls", "Use TURN even when a direct connection is possible", "calls", "Network"),
        c("calls.ringtone", "Ringtone", "Played while an incoming call is ringing", "calls", "Sounds", "ward",
            "ward" to "Ward default", "system" to "System default", "none" to "Silent"),
        c("calls.ringback", "Calling tone", "Played while an outgoing call is ringing", "calls", "Sounds", "ward",
            "ward" to "Ward default", "system" to "System default", "none" to "Silent"),

        // --------------------------------------------------------- Attachments
        c("attachments.image_compression", "Image compression", "Compress outgoing photos before upload", "attachments", "Sending", "large",
            "off" to "Off", "large" to "Large images only", "always" to "Always"),
        c("attachments.video_quality", "Video quality", "Quality used when preparing outgoing videos", "attachments", "Sending", "medium",
            "low" to "Low (240p)", "medium" to "Medium (360p)", "high" to "High (720p)", "original" to "Original"),
        c("attachments.auto_download", "Automatic download", "Maximum file size downloaded automatically", "attachments", "Receiving", 1024,
            0 to "Never", 256 to "256 KiB", 1024 to "1024 KiB", 5120 to "5 MiB", 20480 to "20 MiB"),
        b("attachments.accept_unmetered", "Always accept when unmetered", "Don't use the size limit on Wi-Fi and other unmetered networks", "attachments", "Receiving", true),
        b("attachments.hide_gallery", "Hide media from the gallery", "Keep received and sent media out of system gallery apps", "attachments", "Receiving"),

        // ------------------------------------------------------- Notifications
        b("notify.enabled", "Notifications", "Show a notification when a message arrives", "notifications", "Notifications", true),
        c("notify.content", "Show in notification", "", "notifications", "Notifications", "full",
            "full" to "Sender and text", "sender" to "Sender only", "nothing" to "Nothing"),
        b("notify.hide_encrypted", "Hide text for encrypted chats", "", "notifications", "Notifications", true),
        c("notify.groups", "Group chats", "", "notifications", "Notifications", "mentions",
            "all" to "All messages", "mentions" to "Mentions and replies", "none" to "None"),
        l("notify.highlight_words", "Highlight words", "Your nickname always counts; one word per line", "notifications", "Highlights", defaultHighlightWords),
        SettingSpec("notify.quiet_hours", "Daily quiet hours", "Silence notifications in this range, for example 22:00-07:00", "notifications", "Quiet hours", SettingType.TIME_RANGE, ""),
        b("notify.badge", "Unread count on the app icon", "", "notifications", "Notifications", true),
        b("notify.sounds", "Notification sounds", "Play a sound for new messages outside quiet hours", "notifications", "Sounds", true),

        // ---------------------------------------------------------- Encryption
        c("encryption.default", "Default encryption", "Used for new chats; each chat can override it", "encryption", "Encryption", "always",
            "always" to "Always (recommended)", "available" to "When available", "off" to "Off"),
        c("encryption.trust", "Trust new devices", "What Ward does the first time a contact adds a device", "encryption", "Encryption", "btbv",
            "btbv" to "Blind trust before verification", "manual" to "Ask every time"),
        b("encryption.legacy_omemo", "Allow OMEMO (legacy 0.3)", "Required for Default (OMEMO) and most other XMPP apps", "encryption", "Encryption", true),
        b("pgp.enabled", "OpenPGP", "Optional end-to-end encryption with GnuPG keys (XEP-0373)", "encryption", "OpenPGP"),

        // ------------------------------------------------------------- Privacy
        b("privacy.strip_metadata", "Strip photo location and camera data", "Remove EXIF metadata before sending an image", "privacy", "Photos", true),
        c("privacy.strangers", "Messages from strangers", "What to do when someone you have not added writes to you", "privacy", "Strangers", "requests",
            "requests" to "Requests folder", "block" to "Block", "allow" to "Allow"),

        // ------------------------------------------------------------ Security
        b("security.block_screenshots", "Block screenshots and screen recording",
            "Also hides Ward in the recent-apps preview. QR codes and media cannot be captured while this is on.",
            "security", "Screen privacy"),

        // ------------------------------------------------------------ Advanced
        i("advanced.keepalive", "Keepalive ping interval", "Seconds between pings that keep the connection alive", "advanced", "Connection", 60, 30, 600, 30, "seconds"),
        i("advanced.reconnect_cap", "Reconnect delay cap", "Longest wait between reconnection attempts", "advanced", "Connection", 5, 1, 30, 1, "minutes")
    )
    val byKey = specs.associateBy { it.key }
    fun defaults(): Map<String, Any> = specs.associate { it.key to it.default }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ward_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<String, String>> = _state

    private fun load(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (spec in SettingsSchema.specs) {
            val d = when (val v = spec.default) { is List<*> -> v.joinToString("\n"); else -> v.toString() }
            m[spec.key] = prefs.getString(spec.key, d) ?: d
        }
        if (m["appearance.accent"] == "system") {
            m["appearance.accent"] = "blue"
            prefs.edit().putString("appearance.accent", "blue").apply()
        }
        if (m["chats.send_with"] == "ctrl_enter") {
            m["chats.send_with"] = "newline"
            prefs.edit().putString("chats.send_with", "newline").apply()
        }
        return m
    }
    fun string(key: String, def: String = ""): String = _state.value[key] ?: def
    fun bool(key: String): Boolean = string(key, "false").toBoolean()
    fun int(key: String): Int = string(key, "0").toIntOrNull() ?: 0
    fun list(key: String): List<String> = string(key).split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    fun set(key: String, value: Any) {
        val s = when (value) { is List<*> -> value.joinToString("\n"); else -> value.toString() }
        prefs.edit().putString(key, s).apply()
        _state.value = _state.value + (key to s)
    }
    fun setString(key: String, value: String) = set(key, value)
    fun isDark(): Boolean = when (string("appearance.style", "system")) { "dark" -> true; "light" -> false; else -> false }
    fun accent(): String = string("appearance.accent", "system")

    // Not user-facing settings: bookkeeping for the daily GitHub release check.
    fun lastUpdateCheck(): Long = prefs.getLong("updates.last_check", 0L)
    fun setLastUpdateCheck(time: Long) { prefs.edit().putLong("updates.last_check", time).apply() }
    fun skippedUpdateVersion(): String = prefs.getString("updates.skipped", "").orEmpty()
    fun skipUpdateVersion(version: String) { prefs.edit().putString("updates.skipped", version).apply() }
}
