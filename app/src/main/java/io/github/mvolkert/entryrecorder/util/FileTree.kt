package io.github.mvolkert.entryrecorder.util

import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * A Timber [Tree] that appends every log line to the bounded, rolling file set maintained by a
 * [RollingLogWriter] under [dir]. Planted only while the user has enabled file capture in Settings, and uprooted
 * the moment they turn it off — so disabling leaves no growing file on disk.
 *
 * Writes are posted to a single [HandlerThread]: file I/O never runs on the main thread or a UI frame, and the
 * one-thread serialization keeps lines in order. A line is rendered `"<timestamp> <P>/<tag>: <message>"` with any
 * throwable's stack trace appended, so the exported file reads like logcat without a computer.
 */
class FileTree(
    dir: File,
    maxBytes: Long,
    maxGenerations: Int,
) : Timber.Tree() {
    private val thread = HandlerThread("FileTree").apply { start() }
    private val handler = Handler(thread.looper)
    private val writer = RollingLogWriter(dir, maxBytes, maxGenerations)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val stamp = timestampFormat.format(Date(System.currentTimeMillis()))
        val line = formatLine(stamp, priority, tag, message, t)
        // Writing off the caller's thread is the whole point of the HandlerThread; a disk failure must never
        // crash the app over a lost diagnostic line, so the write is best-effort.
        handler.post { runCatching { writer.append(line) } }
    }

    private fun formatLine(
        stamp: String,
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ): String = buildString {
        append(stamp)
        append(' ').append(priorityChar(priority))
        if (!tag.isNullOrEmpty()) append('/').append(tag)
        append(": ").append(message)
        if (t != null) {
            val trace = StringWriter()
            t.printStackTrace(PrintWriter(trace))
            append('\n').append(trace)
        }
    }

    /** Priority as the single letter logcat uses (V/D/I/W/E/A); Timber's values match `android.util.Log`. */
    private fun priorityChar(priority: Int): Char = when (priority) {
        2 -> 'V'
        3 -> 'D'
        4 -> 'I'
        5 -> 'W'
        6 -> 'E'
        7 -> 'A'
        else -> '?'
    }
}
