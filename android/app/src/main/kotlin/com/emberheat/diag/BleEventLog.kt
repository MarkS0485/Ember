package com.emberheat.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

// In-app diagnostic log for the BLE/protocol layer. A bounded ring buffer
// that (a) mirrors everything to android.util.Log so `adb logcat -s
// HcaloryProtocol` capture keeps working exactly as before, and (b) keeps
// the recent history in memory so the user can view/Share it on-device —
// no PC required. Designed to stay in the shipped build: it's the
// bug-report mechanism once the app is on the Play Store.
//
// Thread-safe: writeRaw runs on an IO coroutine while RX arrives on the
// GATT callback thread, so every mutation is synchronized.
object BleEventLog {

    // Severity, so the UI can colour-code and the export can be filtered.
    enum class Level { INFO, WARN, ERROR }

    // Category groups one line by kind for quick scanning / filtering.
    //   BTN  = a user button/action requested
    //   TX   = a frame written to the heater (+ GATT result)
    //   RX   = a frame received (raw)
    //   STATE= decoded device status, only when it changes
    //   AUTH = login / authentication
    //   CONN = connect / disconnect / GATT lifecycle
    //   WARN = anything gone wrong
    data class Entry(
        val timeMs: Long,
        val level: Level,
        val category: String,
        val message: String,
    )

    private const val CAP = 4000
    private const val TAG = "HcaloryProtocol"

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAP)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Snapshot list, oldest-first. Recomposed whenever a line is added. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(category: String, message: String, level: Level = Level.INFO) {
        // Mirror to logcat under the existing tag so nothing that relied on
        // `adb logcat -s HcaloryProtocol` changes.
        when (level) {
            Level.INFO  -> Log.i(TAG, "$category $message")
            Level.WARN  -> Log.w(TAG, "$category $message")
            Level.ERROR -> Log.e(TAG, "$category $message")
        }
        val entry = Entry(System.currentTimeMillis(), level, category, message)
        synchronized(lock) {
            if (buffer.size >= CAP) buffer.pollFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    fun warn(category: String, message: String) = log(category, message, Level.WARN)
    fun error(category: String, message: String) = log(category, message, Level.ERROR)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Whole buffer as shareable plain text, oldest-first. */
    fun exportText(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        val sb = StringBuilder(snapshot.size * 48)
        sb.append("Ember — BLE diagnostic log\n")
        sb.append("Exported ").append(stamp.format(Date())).append('\n')
        sb.append("Entries: ").append(snapshot.size).append("\n\n")
        for (e in snapshot) {
            sb.append(stamp.format(Date(e.timeMs)))
                .append(' ').append(levelChar(e.level))
                .append(' ').append(e.category)
                .append(' ').append(e.message)
                .append('\n')
        }
        return sb.toString()
    }

    fun format(e: Entry): String =
        "${stamp.format(Date(e.timeMs))} ${e.category} ${e.message}"

    private fun levelChar(l: Level) = when (l) {
        Level.INFO -> "I"; Level.WARN -> "W"; Level.ERROR -> "E"
    }
}
