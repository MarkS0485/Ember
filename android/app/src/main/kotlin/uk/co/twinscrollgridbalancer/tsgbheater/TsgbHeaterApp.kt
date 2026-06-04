package uk.co.twinscrollgridbalancer.tsgbheater

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.diag.DebugTxReceiver
import uk.co.twinscrollgridbalancer.tsgbheater.service.HeaterNotification

class TsgbHeaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        HeaterNotification.ensureChannel(this)

        // ADB TX probe (see DebugTxReceiver). Exported so
        // `adb shell am broadcast` can reach it. Harmless in normal use —
        // nothing sends this action, and it only forwards to an already-
        // connected HCalory link. TODO: gate behind a debug flag / strip
        // before Play Store release (BuildConfig isn't generated in this
        // project, so no DEBUG guard available here yet).
        ContextCompat.registerReceiver(
            this,
            DebugTxReceiver(),
            IntentFilter(DebugTxReceiver.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
