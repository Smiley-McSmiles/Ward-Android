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

object SettingsSchema {
    val pages = listOf(
        "general" to "General", "security" to "Security", "appearance" to "Appearance", "chats" to "Chats", "calls" to "Calls",
        "notifications" to "Notifications", "accounts" to "Accounts", "encryption" to "Encryption",
        "storage" to "Storage", "advanced" to "Advanced"
    )
    private fun b(key: String, title: String, sub: String, page: String, group: String, def: Boolean = false, adv: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.BOOL, def, advanced = adv)
    private fun c(key: String, title: String, sub: String, page: String, group: String, def: Any, vararg choices: Pair<Any, String>, adv: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.CHOICE, def, choices.map { SettingChoice(it.first, it.second) }, advanced = adv)
    private fun i(key: String, title: String, sub: String, page: String, group: String, def: Int, min: Int, max: Int, step: Int = 1, unit: String = "", adv: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.INT, def, min = min, max = max, step = step, unit = unit, advanced = adv)
    private fun t(key: String, title: String, sub: String, page: String, group: String, def: String = "", adv: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.TEXT, def, advanced = adv)
    private fun l(key: String, title: String, sub: String, page: String, group: String, def: List<String> = emptyList(), adv: Boolean = false) =
        SettingSpec(key, title, sub, page, group, SettingType.LIST, def, advanced = adv)

    val defaultReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")
    val specs: List<SettingSpec> = listOf(
        b("general.background", "Run in the background", "Stay connected when the app is closed, so messages and notifications still arrive", "general", "Running", true),
        b("general.autostart", "Start at login", "Start hidden, in the background", "general", "Running"),
        c("general.tray", "Persistent notification", "Keep Ward's connection visible in the notification shade", "general", "Running", "auto", "auto" to "Automatic", "always" to "Always", "never" to "Never"),
        b("general.confirm_quit", "Confirm before quitting", "", "general", "Running"),
        b("general.uri_handler", "Open xmpp: links with Ward", "Links to contacts and groups in your browser open the right chat", "general", "Links", true),
        b("general.search_provider", "Show contacts in system search", "Let Android search find your contacts and chats by name", "general", "Links"),
        b("general.battery_optimization", "Battery optimization help", "Ask Android not to stop Ward in the background", "general", "Running", true),

        c("appearance.style", "Style", "", "appearance", "Colours", "system", "system" to "Follow system", "light" to "Light", "dark" to "Dark"),
        c("appearance.accent", "Accent colour", "", "appearance", "Colours", "blue", "blue" to "Blue", "teal" to "Teal", "green" to "Green", "yellow" to "Yellow", "orange" to "Orange", "red" to "Red", "pink" to "Pink", "purple" to "Purple", "slate" to "Slate"),
        b("appearance.nick_colors", "Coloured nicknames", "The same colour for a person as in Conversations and Dino", "appearance", "Colours", true),
        i("appearance.text_scale", "Text size", "", "appearance", "Text", 100, 80, 200, 10, "%"),
        c("appearance.line_spacing", "Line spacing", "", "appearance", "Text", "normal", "compact" to "Compact", "normal" to "Normal", "relaxed" to "Relaxed"),
        b("appearance.large_emoji", "Large emoji in emoji-only messages", "", "appearance", "Text", true),
        c("appearance.layout", "Message layout", "", "appearance", "Messages", "cozy", "bubbles" to "Bubbles", "cozy" to "Cozy: names and avatars", "compact" to "Compact: like IRC"),
        b("appearance.chat_animations", "Animate chats", "Slide a chat up into the message area when you open it", "appearance", "Messages", true),
        b("appearance.message_animations", "Animate messages", "New messages slide up into the conversation", "appearance", "Messages", true),
        c("appearance.avatars", "Avatars in chats", "", "appearance", "Messages", "always", "always" to "Always", "groups" to "Groups only", "never" to "Never"),
        c("appearance.avatar_shape", "Avatar shape", "", "appearance", "Messages", "circle", "circle" to "Circle", "rounded" to "Rounded square"),
        c("appearance.timestamps", "Timestamps", "", "appearance", "Messages", "always", "always" to "Always", "hover" to "On hover", "off" to "Off"),
        c("appearance.time_format", "Time format", "", "appearance", "Messages", "system", "system" to "Follow system", "12" to "12-hour", "24" to "24-hour"),
        b("appearance.time_seconds", "Show seconds", "", "appearance", "Messages", false, true),
        c("appearance.list_density", "Chat list density", "", "appearance", "Chat list", "comfortable", "comfortable" to "Comfortable", "compact" to "Compact"),
        b("appearance.list_previews", "Message previews in the chat list", "", "appearance", "Chat list", true),
        t("appearance.message_font", "Message font", "Empty uses the system font", "appearance", "Text", "", true),
        t("appearance.code_font", "Code font", "Empty uses the system monospace font", "appearance", "Text", "", true),

        c("chats.send_with", "Send with", "Choose whether Enter sends or starts a new line", "chats", "Writing", "enter", "enter" to "Enter sends", "newline" to "Enter adds a new line"),
        b("chats.toolbar", "Formatting toolbar", "", "chats", "Writing"),
        b("chats.shortcodes", "Convert :shortcodes: to emoji", "", "chats", "Writing", true),
        b("chats.ascii_smileys", "Convert smileys like :) to emoji", "", "chats", "Writing"),
        b("chats.spellcheck", "Spell check", "", "chats", "Writing", true),
        l("chats.quick_reactions", "Quick reactions", "The six emoji in the reaction bar", "chats", "Messages", defaultReactions),
        b("chats.markdown_others", "Render Markdown from other apps", "Other apps use simpler styling, where *text* is bold instead of italic", "chats", "Messages"),
        i("chats.collapse_after", "Collapse long messages after", "Lines; 0 never collapses", "chats", "Messages", 25, 0, 100, 5, "lines"),
        c("chats.animations", "Play animated images", "", "chats", "Messages", "always", "always" to "Always", "hover" to "On hover", "never" to "Never"),
        c("chats.link_previews", "Link previews", "Fetching a preview shows your IP address to the website", "chats", "Messages", "contacts", "off" to "Off", "contacts" to "Only from contacts", "always" to "Always"),
        b("chats.confirm_delete", "Confirm “Delete for everyone”", "", "chats", "Messages", true),
        b("chats.send_typing", "Send typing indicators", "", "chats", "Sharing", true),
        b("chats.show_typing", "Show typing indicators", "", "chats", "Sharing", true),
        b("chats.send_markers", "Send read markers", "", "chats", "Sharing", true),
        b("chats.send_receipts", "Send delivery receipts", "", "chats", "Sharing", true),
        c("chats.join_leave", "Join and leave lines in groups", "", "chats", "Groups", "summary", "summary" to "Summary", "all" to "Every line", "hidden" to "Hidden"),
        c("chats.voice_quality", "Voice message quality", "", "chats", "Media", "standard", "standard" to "Standard", "high" to "High"),

        b("calls.enabled", "Audio and video calls", "Contacts can call you, and you them, on this device", "calls", "Calls", true),
        i("calls.mic_gain", "Microphone volume", "Applied to the microphone before encoding", "calls", "Audio", 100, 0, 200, 5, "%"),
        i("calls.speaker_gain", "Speaker volume", "Applied to the other side's audio", "calls", "Audio", 100, 0, 200, 5, "%"),
        b("calls.discover", "Discover STUN/TURN from the server", "Use XEP-0215 external service discovery for call relay servers", "calls", "Network", true),
        t("calls.stun", "STUN servers", "Space-separated stun:host:port addresses", "calls", "Network"),
        t("calls.turn", "TURN servers", "Space-separated turn:host:port addresses; add ?transport=tcp for TCP", "calls", "Network"),
        t("calls.turn_user", "TURN username", "", "calls", "Network"),
        t("calls.turn_password", "TURN password", "", "calls", "Network"),
        t("calls.turn_secret", "TURN shared secret", "For time-limited credentials, as used by coturn and eturnal", "calls", "Network", "", true),
        i("calls.turn_ttl", "TURN credential lifetime", "Seconds; used with the shared secret", "calls", "Network", 86400, 300, 604800, 3600, "seconds", true),
        b("calls.relay_only", "Always relay calls", "Use TURN even when a direct connection is possible", "calls", "Network", true, true),
        t("calls.ringtone", "Ringtone", "System ringtone; empty uses the default", "calls", "Sounds"),
        t("calls.ringback", "Calling tone", "System tone; empty uses the default", "calls", "Sounds"),

        b("notify.enabled", "Notifications", "", "notifications", "Notifications", true),
        c("notify.content", "Show in notification", "", "notifications", "Notifications", "full", "full" to "Sender and text", "sender" to "Sender only", "nothing" to "Nothing"),
        b("notify.hide_encrypted", "Hide text for encrypted chats", "", "notifications", "Notifications", true),
        c("notify.groups", "Group chats", "", "notifications", "Notifications", "mentions", "all" to "All messages", "mentions" to "Mentions and replies", "none" to "None"),
        l("notify.highlight_words", "Highlight words", "Your nickname always counts; one word per line", "notifications", "Notifications"),
        SettingSpec("notify.quiet_hours", "Quiet hours", "No sounds or pop-ups in this daily range", "notifications", "Notifications", SettingType.TIME_RANGE, ""),
        b("notify.badge", "Unread count on the app icon", "", "notifications", "Notifications", true),
        b("notify.sounds", "Sounds", "", "notifications", "Sounds", true),

        c("encryption.default", "Default encryption", "Each account and chat can override this", "encryption", "Encryption", "always", "always" to "Always (recommended)", "available" to "When available", "off" to "Off"),
        c("encryption.trust", "Trust new devices", "", "encryption", "Encryption", "btbv", "btbv" to "Blind trust before verification", "manual" to "Ask every time"),
        c("encryption.new_device_notice", "Tell me about new devices", "", "encryption", "Encryption", "banner", "banner" to "Chat banner", "notify" to "Banner and notification"),
        c("encryption.inactive_days", "Mark devices inactive after", "", "encryption", "Encryption", 90, 30 to "30 days", 90 to "90 days", 180 to "180 days", 0 to "Never"),
        b("encryption.legacy_omemo", "Allow legacy OMEMO (0.3)", "Most other apps still use it", "encryption", "Encryption", true),
        b("encryption.omemo2", "Prefer OMEMO 2", "Encrypt the whole stanza (SCE) for contacts that support OMEMO 2", "encryption", "Encryption", true),
        b("encryption.limit_legacy_extras", "Limit unencrypted extras with legacy OMEMO", "Legacy OMEMO only encrypts the text: don’t send reactions or typing", "encryption", "Encryption", false, true),
        b("pgp.enabled", "OpenPGP", "Optional end-to-end encryption with GnuPG keys (XEP-0373)", "encryption", "OpenPGP"),
        b("privacy.strip_metadata", "Strip image location and camera data", "Before sending a photo", "encryption", "Privacy", true),
        c("privacy.strangers", "Messages from strangers", "", "encryption", "Privacy", "requests", "requests" to "Requests folder", "block" to "Block", "allow" to "Allow"),
        b("privacy.stranger_media", "Load images and previews from strangers", "", "encryption", "Privacy"),

        c("storage.cache_limit", "Cache size limit", "Downloaded files, thumbnails, avatars and previews", "storage", "Cache", 1_000_000_000, 250_000_000 to "250 MB", 1_000_000_000 to "1 GB", 5_000_000_000 to "5 GB", 0 to "Unlimited"),
        c("storage.delete_files_after", "Delete downloaded files after", "", "storage", "Cache", 0, 30 to "30 days", 90 to "90 days", 365 to "365 days", 0 to "Never"),

        b("advanced.devtools", "Developer tools", "XML console, service discovery and PEP browser", "advanced", "Developer"),
        c("advanced.debug_log", "Debug log", "", "advanced", "Developer", "off", "off" to "Off", "normal" to "Normal", "verbose" to "Verbose"),
        i("advanced.keepalive", "Keepalive ping interval", "", "advanced", "Connection", 60, 30, 600, 30, "seconds"),
        i("advanced.reconnect_cap", "Reconnect delay cap", "", "advanced", "Connection", 5, 1, 30, 1, "minutes"),
        c("advanced.ip_preference", "Internet protocol", "", "advanced", "Connection", "auto", "auto" to "Automatic", "ipv4" to "Prefer IPv4", "ipv6" to "Prefer IPv6"),
        i("advanced.spk_rotation_days", "Signed prekey rotation", "", "advanced", "OMEMO", 7, 7, 30, 1, "days"),
        i("advanced.prekey_threshold", "Refill prekeys below", "", "advanced", "OMEMO", 99, 25, 99, 1)
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
}
