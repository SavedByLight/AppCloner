package com.savedbylight.appcloner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal logger for clone operations. Keeps an in-memory list for the
 * current process and appends to a file under filesDir so the Log page
 * still has history after the app is killed/reopened.
 */
object Logger {

    private val entries = mutableListOf<String>()
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(context.filesDir, "clone_log.txt")
        logFile?.takeIf { it.exists() }?.readLines()?.let { entries.addAll(it) }
    }

    fun log(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        entries.add(line)
        logFile?.appendText(line + "\n")
    }

    fun all(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
        logFile?.writeText("")
    }
}
