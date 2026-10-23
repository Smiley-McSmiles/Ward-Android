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
            // which could leave a legacy OMEMO identity that other clients reject. The public
            // version line starts at 1.0.0 / versionCode 1, so keep this migration scoped to the
            // old development version codes rather than every versionCode below 9.
            if (previousCode in 2..8) {
                runCatching { java.io.File(filesDir, "omemo").deleteRecursively() }
                runCatching { filesDir.listFiles { f -> f.name.startsWith("omemo2-") }?.forEach { it.delete() } }
                runCatching { java.io.File(filesDir, "reset-legacy-omemo").createNewFile() }
            }
            versionPrefs.edit().putInt("code", BuildConfig.VERSION_CODE).apply()
        }
        CrashHandler.install(this)
        org.jivesoftware.smack.android.AndroidSmackInitializer.initialize(this)
        WardCore.init(this)
    }
}
