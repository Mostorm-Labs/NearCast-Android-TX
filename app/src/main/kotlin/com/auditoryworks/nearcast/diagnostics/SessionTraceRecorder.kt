package com.auditoryworks.nearcast.diagnostics

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object SessionTraceRecorder {
    private const val MAX_ENTRIES = 400
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    fun record(source: String, message: String) {
        synchronized(lock) {
            entries.addLast("${timestamp()} [$source] $message")
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(): String {
        return synchronized(lock) {
            if (entries.isEmpty()) {
                "No session trace recorded.\n"
            } else {
                entries.joinToString(separator = "\n", postfix = "\n")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return format.format(Date())
    }
}
