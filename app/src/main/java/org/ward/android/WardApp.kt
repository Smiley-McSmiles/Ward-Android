package org.ward.android
import android.app.Application
class WardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val versionPrefs = getSharedPreferences("ward_version", MODE_PRIVATE)
        val previousCode = versionPrefs.getInt("code", 0)
        if (previousCode != BuildConfig.VERSION_CODE) {
            CrashHandler.clear(this)
            // Early pre-release builds (up to 1.0.6) used the Dino libsignal fork for a while,
            // which could leave a legacy OMEMO identity that other clients reject. Do this at most
            // once per installation, and never on ordinary version updates: OMEMO identities,
            // pre-keys and sessions must survive app updates.
            val legacyResetDone = versionPrefs.getBoolean("legacy_omemo_reset_done", false)
            if (!legacyResetDone) {
                if (previousCode in 2..8) {
                    runCatching { java.io.File(filesDir, "omemo").deleteRecursively() }
                    runCatching { filesDir.listFiles { f -> f.name.startsWith("omemo2-") }?.forEach { it.delete() } }
                    runCatching { java.io.File(filesDir, "reset-legacy-omemo").createNewFile() }
                }
                versionPrefs.edit().putBoolean("legacy_omemo_reset_done", true).apply()
            }
            versionPrefs.edit().putInt("code", BuildConfig.VERSION_CODE).apply()
        }
        CrashHandler.install(this)
        org.jivesoftware.smack.android.AndroidSmackInitializer.initialize(this)
        WardCore.init(this)
    }
}
