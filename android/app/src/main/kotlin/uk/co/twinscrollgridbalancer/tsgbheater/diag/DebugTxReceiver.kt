package uk.co.twinscrollgridbalancer.tsgbheater.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

// ADB-triggerable TX hook for live protocol probing. Registered in
// TsgbHeaterApp.onCreate (debug builds only). Lets us fire arbitrary frames
// at a connected HCalory heater without rebuilding, e.g.:
//
//   adb shell am broadcast -a uk.co.twinscrollgridbalancer.tsgbheater.DEBUG_TX \
//       --es hex 000200010001000A08000001 01 --es mode csum
//
// Extras:
//   hex  = the frame as hex (spaces/colons ignored). For mode=csum, give the
//          frame WITHOUT the trailing checksum byte (it's appended). For
//          mode=exact, give the COMPLETE frame including checksum.
//   mode = "csum" (default) appends payload checksum; "exact" sends verbatim.
//
// This is a diagnostic affordance; it only does anything while an HCalory
// link is live, and writes nothing if the active driver isn't HCalory.
class DebugTxReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hex = intent.getStringExtra("hex") ?: run {
            BleEventLog.warn("DBG", "broadcast missing 'hex' extra")
            return
        }
        val exact = (intent.getStringExtra("mode") ?: "csum").equals("exact", ignoreCase = true)
        val bytes = parseHex(hex) ?: run {
            BleEventLog.warn("DBG", "bad hex: '$hex'")
            return
        }
        BleEventLog.log("DBG", "broadcast hex=${bytes.joinToString("") { "%02X".format(it) }} mode=${if (exact) "exact" else "csum"}")
        ServiceLocator.ble.debugHcalorySend(bytes, exact)
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.filter { it.isLetterOrDigit() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    companion object {
        const val ACTION = "uk.co.twinscrollgridbalancer.tsgbheater.DEBUG_TX"
    }
}
