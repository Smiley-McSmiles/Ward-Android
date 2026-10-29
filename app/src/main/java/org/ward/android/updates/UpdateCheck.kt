package org.ward.android.updates

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String)

/**
 * Asks the public GitHub release feed for the newest Ward for Android build. The API is
 * unauthenticated, so call it at most once a day and only when the user allows it.
 */
object UpdateCheck {
    const val API = "https://api.github.com/repos/Smiley-McSmiles/Ward-Android/releases/latest"
    const val RELEASES_URL = "https://github.com/Smiley-McSmiles/Ward-Android/releases/latest"

    /** The latest published (non-draft, non-prerelease) release, or null when it cannot be read. */
    fun latest(): UpdateInfo? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Ward-Android")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (version.isBlank()) return null
            UpdateInfo(version, json.optString("html_url").ifBlank { RELEASES_URL })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Compare dotted versions numerically, so 1.0.10 is newer than 1.0.9. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
