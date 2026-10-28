package org.ward.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ward.android.util.AppLockPolicy

class AppLockPolicyTest {
    private val delay = 15_000L

    @Test
    fun disabledLockAlwaysStartsUnlocked() {
        assertTrue(AppLockPolicy.startUnlocked(
            enabled = false, sessionUnlocked = false, externalActivity = false,
            backgroundAt = 0L, now = 1_000L, lockDelayMs = 0L))
    }

    /** Regression: a second activity (notification tap) while Ward never went to background. */
    @Test
    fun sessionStaysUnlockedWithoutBackground() {
        assertTrue(AppLockPolicy.startUnlocked(
            enabled = true, sessionUnlocked = true, externalActivity = false,
            backgroundAt = 0L, now = 1_000L, lockDelayMs = delay))
    }

    @Test
    fun freshProcessWithoutBackgroundStartsLocked() {
        assertFalse(AppLockPolicy.startUnlocked(
            enabled = true, sessionUnlocked = false, externalActivity = false,
            backgroundAt = 0L, now = 1_000L, lockDelayMs = delay))
    }

    @Test
    fun returningWithinDelayStartsUnlocked() {
        assertTrue(AppLockPolicy.startUnlocked(
            enabled = true, sessionUnlocked = false, externalActivity = false,
            backgroundAt = 5_000L, now = 15_000L, lockDelayMs = delay))
    }

    @Test
    fun returningAfterDelayStartsLocked() {
        assertFalse(AppLockPolicy.startUnlocked(
            enabled = true, sessionUnlocked = false, externalActivity = false,
            backgroundAt = 5_000L, now = 20_000L, lockDelayMs = delay))
    }

    @Test
    fun externalActivityKeepsUnlocked() {
        assertTrue(AppLockPolicy.startUnlocked(
            enabled = true, sessionUnlocked = false, externalActivity = true,
            backgroundAt = 0L, now = 1_000L, lockDelayMs = 0L))
    }

    @Test
    fun zeroDelayLocksOnAnyReturn() {
        assertTrue(AppLockPolicy.lockOnReturn(backgroundAt = 5_000L, now = 5_001L, lockDelayMs = 0L))
    }

    @Test
    fun returnWithoutBackgroundDoesNotLock() {
        assertFalse(AppLockPolicy.lockOnReturn(backgroundAt = 0L, now = 9_000L, lockDelayMs = delay))
    }

    @Test
    fun lockOnReturnHonoursDelay() {
        assertFalse(AppLockPolicy.lockOnReturn(backgroundAt = 5_000L, now = 15_000L, lockDelayMs = delay))
        assertTrue(AppLockPolicy.lockOnReturn(backgroundAt = 5_000L, now = 20_000L, lockDelayMs = delay))
    }

    @Test
    fun clockGoingBackwardsDoesNotLock() {
        assertFalse(AppLockPolicy.lockOnReturn(backgroundAt = 5_000L, now = 4_000L, lockDelayMs = delay))
    }
}
