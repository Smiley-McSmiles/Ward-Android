package org.ward.android.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object JidUtil {
    fun bare(jid: String): String {
        var s = jid.trim().removePrefix("xmpp:")
        s = s.substringBefore('/').substringBefore('?')
        return s.lowercase(Locale.ROOT)
    }
    fun domain(jid: String): String = bare(jid).substringAfter('@', bare(jid))
    fun localpart(jid: String): String = bare(jid).substringBefore('@')
    fun valid(jid: String): Boolean {
        val b = bare(jid)
        val at = b.indexOf('@')
        return at > 0 && at < b.length - 1 && b.substringAfter('@').contains('.') && !b.any { it.isWhitespace() }
    }
    fun nick(jid: String): String = localpart(jid).ifBlank { bare(jid) }
    fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(24)
}

object Colors {
    private val palette = listOf("#e01b24", "#e66100", "#c64600", "#a51d2d", "#9141ac", "#613583",
        "#1c71d8", "#3584e4", "#2190a4", "#26a269", "#2ec27e", "#c88800", "#986a44")
    fun forJid(text: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(text.lowercase(Locale.ROOT).toByteArray())
        var hue = ((d[0].toInt() and 0xff) or ((d[1].toInt() and 0xff) shl 8))
        // Convert an XEP-0392-ish hue into one of the GNOME palette colours for readable contrast.
        val idx = ((hue % 360 + 360) % 360) * palette.size / 360
        return palette[idx.coerceIn(0, palette.size - 1)]
    }
    fun initials(name: String): String {
        val words = name.replace("@", " ").replace(".", " ").replace("_", " ").split(' ').filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }
    fun avatarText(name: String) = initials(name)
}

object TimeUtil {
    /** Resolve the "system", 12-hour or 24-hour preference into a concrete clock style. */
    fun twelveHour(context: Context, setting: String): Boolean = when (setting) {
        "12" -> true
        "24" -> false
        else -> !android.text.format.DateFormat.is24HourFormat(context)
    }

    fun clock(ts: Long, seconds: Boolean = false, twelveHour: Boolean? = null): String {
        val is12 = twelveHour ?: false
        val fmt = if (is12) (if (seconds) "h:mm:ss a" else "h:mm a") else (if (seconds) "HH:mm:ss" else "HH:mm")
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ts))
    }
    fun day(ts: Long): String {
        val now = System.currentTimeMillis()
        val d = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
        val yesterday = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now - 86_400_000))
        return when (d) {
            today -> "Today"; yesterday -> "Yesterday"
            else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
        }
    }
    fun relative(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} h ago"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} d ago"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
    fun listStamp(ts: Long, seconds: Boolean = false, twelveHour: Boolean? = null): String {
        val now = System.currentTimeMillis()
        return when {
            now - ts < 86_400_000 && SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts)) ==
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) -> clock(ts, seconds, twelveHour)
            now - ts < 7 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
}

object SecretStore {
    private const val ALIAS = "ward_password_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        "enc:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (_: Exception) { "b64:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP) }
    fun decrypt(stored: String): String = try {
        when {
            stored.startsWith("enc:") -> {
                val (iv, data) = stored.removePrefix("enc:").split(':', limit = 2)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
            }
            stored.startsWith("b64:") -> String(Base64.decode(stored.removePrefix("b64:"), Base64.NO_WRAP))
            else -> stored
        }
    } catch (_: Exception) { "" }
}

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
fun now(): Long = System.currentTimeMillis()
