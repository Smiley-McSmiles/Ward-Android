package org.ward.android

import android.content.Context

object CrashHandler {
    private const val FILE = "last_crash.txt"
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = java.io.StringWriter()
                sw.append("Ward crash on ").append(java.util.Date().toString()).append("\n")
                sw.append(thread.name).append("\n\n")
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val text = sw.toString()
                context.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
    fun read(context: Context): String? = runCatching {
        context.openFileInput(FILE).bufferedReader().use { it.readText() }
    }.getOrNull()
    fun clear(context: Context) { runCatching { context.deleteFile(FILE) } }
}
