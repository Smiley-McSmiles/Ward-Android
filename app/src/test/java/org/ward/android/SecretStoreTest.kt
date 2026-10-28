package org.ward.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.ward.android.util.SecretStore
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretStoreTest {
    @Test
    fun legacyBase64PasswordStillDecrypts() {
        val stored = "b64:" + Base64.getEncoder().encodeToString("correct horse".toByteArray())
        assertEquals("correct horse", SecretStore.decrypt(stored))
    }

    @Test
    fun legacyUnprefixedPasswordStillDecrypts() {
        assertEquals("hunter2", SecretStore.decrypt("hunter2"))
    }
}
