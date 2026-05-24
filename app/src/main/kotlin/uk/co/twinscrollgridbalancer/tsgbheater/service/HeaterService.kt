package uk.co.twinscrollgridbalancer.tsgbheater.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

// Foreground service that owns the live BLE link to the heater. Started by
// MainActivity when the user first binds a device (or on app start if a
// device was previously bound). Survives the activity, so Auto Start/Stop
// rules can fire even when the app is in the background. The notification
// shown for the foreground requirement also doubles as the user-visible
// summary ("Connected to Workshop · 19.4 °C").
class HeaterService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        HeaterNotification.ensureChannel(this)
        startInForeground("TSGB Heater", "Starting…")
        observeConnection()
        // TODO(AutoStartStop): once the user defines the rules, wire a
        //   collector here that observes BleManager.telemetry +
        //   AppSettingsStore.autoStartStopMasterEnabled and calls
        //   bleManager.sendStart() / sendStop() on rule transitions.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC)
                if (!mac.isNullOrBlank()) {
                    Log.i(TAG, "ACTION_CONNECT mac=$mac")
                    ServiceLocator.ble.connect(mac)
                }
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "ACTION_DISCONNECT")
                ServiceLocator.ble.disconnect()
            }
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP — tearing down service")
                ServiceLocator.ble.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceLocator.ble.disconnect()
        super.onDestroy()
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            ServiceLocator.ble.connectionState.collectLatest { state ->
                val label = when (state) {
                    ConnectionState.Idle                 -> "No heater connected"
                    ConnectionState.Scanning             -> "Scanning…"
                    ConnectionState.Connecting           -> "Connecting…"
                    ConnectionState.DiscoveringServices  -> "Discovering services…"
                    ConnectionState.Ready                -> "Connected"
                    ConnectionState.Reconnecting         -> "Reconnecting…"
                    ConnectionState.Failed               -> "Connection failed"
                }
                updateNotification(label)
            }
        }
    }

    private fun startInForeground(title: String, status: String) {
        val notif = HeaterNotification.build(this, title, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                HeaterNotification.NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(HeaterNotification.NOTIF_ID, notif)
        }
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(
            HeaterNotification.NOTIF_ID,
            HeaterNotification.build(this, "TSGB Heater", status),
        )
    }

    companion object {
        private const val TAG = "HeaterService"

        const val ACTION_CONNECT    = "tsgbheater.action.CONNECT"
        const val ACTION_DISCONNECT = "tsgbheater.action.DISCONNECT"
        const val ACTION_STOP       = "tsgbheater.action.STOP"
        const val EXTRA_MAC         = "mac"

        fun connect(ctx: Context, mac: String) {
            val i = Intent(ctx, HeaterService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_MAC, mac)
            }
            ctx.startForegroundService(i)
        }

        fun disconnect(ctx: Context) {
            val i = Intent(ctx, HeaterService::class.java).apply { action = ACTION_DISCONNECT }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, HeaterService::class.java).apply { action = ACTION_STOP }
            ctx.startForegroundService(i)
        }
    }
}
