package org.ward.android.util

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores a salted PBKDF2 hash for the optional app lock. The plaintext PIN, password or pattern is
 * never written to disk, and the credentials live in their own preferences file so app updates
 * never touch them.
 */
object AppLockStore {
    const val OFF = "off"
    const val PIN = "pin"
    const val PASSWORD = "password"
    const val PATTERN = "pattern"

    private const val PREFS = "ward_app_lock"
    private const val KEY_TYPE = "type"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_MAX_ATTEMPTS = "max_attempts"
    private const val KEY_LOCKOUT_SECONDS = "lockout_seconds"
    private const val KEY_LOCK_DELAY_SECONDS = "lock_delay_seconds"
    private const val KEY_BACKGROUND_AT = "background_at"
    private const val KEY_FAILED = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    @Volatile private var externalUntil = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun type(context: Context): String {
        val value = prefs(context).getString(KEY_TYPE, OFF) ?: OFF
        return if (value in setOf(OFF, PIN, PASSWORD, PATTERN)) value else OFF
    }

    fun isEnabled(context: Context): Boolean {
        val p = prefs(context)
        return type(context) != OFF && !p.getString(KEY_HASH, null).isNullOrBlank()
    }

    fun set(context: Context, type: String, secret: String) {
        require(type in setOf(PIN, PASSWORD, PATTERN)) { "Unknown app lock type" }
        require(secret.isNotBlank()) { "Empty app lock secret" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(secret, salt)
        prefs(context).edit()
            .putString(KEY_TYPE, type)
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(KEY_HASH, Base64.getEncoder().encodeToString(hash))
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 0 means unlimited: never wipe because of failed attempts. */
    fun maxAttempts(context: Context): Int = prefs(context).getInt(KEY_MAX_ATTEMPTS, 10).coerceIn(0, 100)
    fun setMaxAttempts(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_ATTEMPTS, value.coerceIn(0, 100)).apply()
    }

    /** Seconds to wait after a failed attempt. 0 disables the cooldown. */
    fun lockoutSeconds(context: Context): Int = prefs(context).getInt(KEY_LOCKOUT_SECONDS, 30).coerceIn(0, 86_400)
    fun setLockoutSeconds(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LOCKOUT_SECONDS, value.coerceIn(0, 86_400)).apply()
    }

    /** How long Ward may stay in the background before the app lock arms. 0 means immediately. */
    fun lockDelaySeconds(context: Context): Int =
        prefs(context).getInt(KEY_LOCK_DELAY_SECONDS, 0).coerceIn(0, 3_600)
    fun setLockDelaySeconds(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LOCK_DELAY_SECONDS, value.coerceIn(0, 3_600)).apply()
    }

    /** Elapsed-realtime timestamp when Ward last went to the background. Survives recreation. */
    fun backgroundAt(context: Context): Long = prefs(context).getLong(KEY_BACKGROUND_AT, 0L)
    fun setBackgroundAt(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_BACKGROUND_AT, value).apply()
    }
    fun clearBackgroundAt(context: Context) {
        prefs(context).edit().remove(KEY_BACKGROUND_AT).apply()
    }

    data class Failure(val attempts: Int, val wipe: Boolean, val lockoutUntil: Long)

    /**
     * The system photo/document picker is a separate activity. It stops Ward briefly, but that
     * must not arm the lock screen: the user is still in the middle of choosing a file. The
     * deadline is a safety net in case an activity result is never delivered.
     */
    fun beginExternalActivity() {
        externalUntil = SystemClock.elapsedRealtime() + 90_000L
    }

    fun endExternalActivity() {
        externalUntil = 0L
    }

    fun externalActivityActive(): Boolean = SystemClock.elapsedRealtime() < externalUntil

    fun recordFailure(context: Context): Failure {
        val p = prefs(context)
        val attempts = p.getInt(KEY_FAILED, 0) + 1
        val max = maxAttempts(context)
        val wipe = max > 0 && attempts >= max
        val lockoutUntil = if (lockoutSeconds(context) > 0)
            System.currentTimeMillis() + lockoutSeconds(context) * 1000L else 0L
        p.edit()
            .putInt(KEY_FAILED, attempts)
            .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            .apply()
        return Failure(attempts, wipe, lockoutUntil)
    }

    fun clearFailures(context: Context) {
        prefs(context).edit().putInt(KEY_FAILED, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
    }

    fun lockoutRemaining(context: Context): Long =
        (prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun failedAttempts(context: Context): Int = prefs(context).getInt(KEY_FAILED, 0)

    fun verify(context: Context, secret: String): Boolean {
        if (secret.isBlank() || !isEnabled(context)) return false
        val p = prefs(context)
        val salt = runCatching { Base64.getDecoder().decode(p.getString(KEY_SALT, "") ?: "") }.getOrNull()
            ?: return false
        val expected = runCatching { Base64.getDecoder().decode(p.getString(KEY_HASH, "") ?: "") }.getOrNull()
            ?: return false
        val actual = derive(secret, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
