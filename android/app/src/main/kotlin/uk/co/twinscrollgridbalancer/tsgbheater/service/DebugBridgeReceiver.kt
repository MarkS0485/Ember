package uk.co.twinscrollgridbalancer.tsgbheater.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Exported relay so debug-only intents from the ADB shell can reach the
// (un-exported) foreground HeaterService. The shell UID can't start a
// non-exported service, but it can broadcast to an exported receiver in
// the same package; this just forwards the action verbatim.
//
// Drive from ADB with:
//   adb shell am broadcast -n uk.co.twinscrollgridbalancer.tsgbheater/.service.DebugBridgeReceiver \
//     -a tsgbheater.action.DEBUG_SEND_HEX --es hex "AA 00 66 05 F4 01 3D B5"
class DebugBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Relaying $action with extras=${intent.extras}")
        val fwd = Intent(ctx, HeaterService::class.java).apply {
            this.action = action
            intent.extras?.let { putExtras(it) }
        }
        ctx.startForegroundService(fwd)
    }

    private companion object { const val TAG = "DebugBridge" }
}
