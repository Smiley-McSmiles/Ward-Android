package org.ward.android.util

/**
 * Pure app-lock decisions.
 *
 * The app lock used to be remembered only inside the Compose tree, so a second Ward activity
 * (for example the one a notification tap pushes on top of the running app) started with no
 * unlocked state and showed the lock screen even though the user had never left the chat.
 *
 * These helpers keep the timing rules out of the UI so they can be unit tested.
 */
object AppLockPolicy {
    /**
     * Whether a freshly created app UI may skip the lock screen.
     *
     * A session that is already unlocked in this process stays unlocked: activity recreation or a
     * notification tap must not re-arm the lock while Ward is in the foreground. Otherwise the
     * usual background-delay rule applies; a zero [backgroundAt] means Ward never went to the
     * background, which is not a reason to lock.
     */
    fun startUnlocked(
        enabled: Boolean,
        sessionUnlocked: Boolean,
        externalActivity: Boolean,
        backgroundAt: Long,
        now: Long,
        lockDelayMs: Long,
    ): Boolean {
        if (!enabled) return true
        if (sessionUnlocked || externalActivity) return true
        return withinDelay(backgroundAt, now, lockDelayMs)
    }

    /** Whether returning to a backgrounded UI must arm the lock screen. */
    fun lockOnReturn(backgroundAt: Long, now: Long, lockDelayMs: Long): Boolean {
        if (backgroundAt <= 0L || now < backgroundAt) return false
        return lockDelayMs <= 0L || now - backgroundAt >= lockDelayMs
    }

    private fun withinDelay(backgroundAt: Long, now: Long, lockDelayMs: Long): Boolean =
        lockDelayMs > 0L && backgroundAt > 0L && now >= backgroundAt && now - backgroundAt < lockDelayMs
}
