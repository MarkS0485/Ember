package com.emberheat.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.emberheat.ble.BleManager
import com.emberheat.ble.ConnectionState
import com.emberheat.ble.FrameCodec
import com.emberheat.di.ServiceLocator
import com.emberheat.widget.HeaterWidgetUpdater

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
        startInForeground("Ember", "Starting…")
        observeConnection()
        observeWidgetState()
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
            ACTION_WIDGET_HEATER_ON  -> runWidgetCommand { ble -> ble.sendStart() }
            ACTION_WIDGET_HEATER_OFF -> runWidgetCommand { ble -> ble.sendStop()  }
            ACTION_WIDGET_BLOWER_ON  -> runWidgetCommand { ble -> ble.blowOn()    }
            // The vendor app stops the blower with CMD_OFF, not CMD_OIL_PUMP_OFF —
            // see docs/BLE_PROTOCOL.md "Manual pump run" note.
            ACTION_WIDGET_BLOWER_OFF -> runWidgetCommand { ble -> ble.sendStop()  }
            // Debug hook: send an arbitrary hex frame. Drive from ADB with
            //   adb shell am start-foreground-service \
            //     -n com.emberheat/.service.HeaterService \
            //     -a ember.action.DEBUG_SEND_HEX --es hex "AA 00 66 05 00 F4"
            // Frame is sent verbatim — no CRC fix-up — so use the SHORT_PARA
            // probe helper or FrameCodec.hex(...) output for valid frames.
            ACTION_DEBUG_DUMP_TELEMETRY -> {
                val t = ServiceLocator.ble.telemetry.value
                Log.i(TAG, "DUMP telemetry=$t")
            }
            ACTION_DEBUG_VERBOSE_RX -> {
                val on = intent.getBooleanExtra(EXTRA_VERBOSE, true)
                // Flip the flag on the singleton connection's verbose log.
                // Property is volatile so toggling from binder thread is fine.
                runCatching {
                    val ble = ServiceLocator.ble
                    val field = ble.javaClass.getDeclaredField("connection")
                    field.isAccessible = true
                    val conn = field.get(ble) as com.emberheat.ble.HeaterConnection
                    conn.verboseRx = on
                    Log.i(TAG, "VERBOSE_RX=$on")
                }.onFailure { Log.w(TAG, "VERBOSE_RX failed", it) }
            }
            ACTION_DEBUG_SEND_HEX -> {
                val hex = intent.getStringExtra(EXTRA_HEX)
                if (!hex.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val bytes = runCatching { FrameCodec.fromHex(hex) }.getOrNull()
                        if (bytes == null) {
                            Log.w(TAG, "DEBUG_SEND_HEX bad hex: $hex")
                        } else {
                            val ok = ServiceLocator.ble.sendRaw(bytes)
                            Log.i(TAG, "DEBUG_SEND_HEX bytes=${bytes.size} ok=$ok hex=${FrameCodec.hex(bytes)}")
                        }
                    }
                }
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

    // Keep the home-screen widget's snapshot fresh. The bound-device name
    // / MAC come from BoundDeviceStore; live status from BleManager.
    private fun observeWidgetState() {
        val store = ServiceLocator.boundDevices
        val ble   = ServiceLocator.ble
        val app   = applicationContext

        lifecycleScope.launch {
            combine(store.currentMac, store.all) { mac, list ->
                mac to list.firstOrNull { it.mac == mac }?.name
            }.collectLatest { (mac, name) ->
                HeaterWidgetUpdater.setBinding(app, mac, name)
            }
        }

        lifecycleScope.launch {
            combine(ble.telemetry, ble.connectionState) { t, s -> t to s }
                .collectLatest { (telemetry, state) ->
                    HeaterWidgetUpdater.setLive(app, telemetry, state)
                }
        }
    }

    // Widget click handler. Auto-connects to the currently-bound heater if
    // the link is down, then sends the BLE command. Bails quietly if no
    // heater is paired so a stale widget can't lock up the service.
    private fun runWidgetCommand(send: suspend (BleManager) -> Boolean) {
        lifecycleScope.launch {
            // Home-screen control widgets are a Pro feature — ignore taps
            // when entitlement is absent so a free/lapsed widget is inert.
            if (!ServiceLocator.entitlements.isProActive.value) {
                Log.w(TAG, "Widget command ignored — Pro required")
                return@launch
            }
            val ble = ServiceLocator.ble
            val mac = ServiceLocator.boundDevices.currentMac.first()
            if (mac.isNullOrBlank()) {
                Log.w(TAG, "Widget command ignored — no current MAC bound")
                return@launch
            }
            if (ble.connectionState.value != ConnectionState.Ready) {
                Log.i(TAG, "Widget command — connecting to $mac first")
                ble.connect(mac)
                val ready = try {
                    withTimeout(WIDGET_CONNECT_TIMEOUT_MS) {
                        ble.connectionState.first { it == ConnectionState.Ready }
                        true
                    }
                } catch (_: TimeoutCancellationException) {
                    false
                }
                if (!ready) {
                    Log.w(TAG, "Widget command timed out waiting for connection")
                    return@launch
                }
            }
            send(ble)
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
            HeaterNotification.build(this, "Ember", status),
        )
    }

    companion object {
        private const val TAG = "HeaterService"
        private const val WIDGET_CONNECT_TIMEOUT_MS = 12_000L

        const val ACTION_CONNECT    = "ember.action.CONNECT"
        const val ACTION_DISCONNECT = "ember.action.DISCONNECT"
        const val ACTION_STOP       = "ember.action.STOP"
        const val EXTRA_MAC         = "mac"

        const val ACTION_WIDGET_HEATER_ON  = "ember.action.WIDGET_HEATER_ON"
        const val ACTION_WIDGET_HEATER_OFF = "ember.action.WIDGET_HEATER_OFF"
        const val ACTION_WIDGET_BLOWER_ON  = "ember.action.WIDGET_BLOWER_ON"
        const val ACTION_WIDGET_BLOWER_OFF = "ember.action.WIDGET_BLOWER_OFF"

        const val ACTION_DEBUG_SEND_HEX        = "ember.action.DEBUG_SEND_HEX"
        const val ACTION_DEBUG_DUMP_TELEMETRY  = "ember.action.DEBUG_DUMP_TELEMETRY"
        const val ACTION_DEBUG_VERBOSE_RX      = "ember.action.DEBUG_VERBOSE_RX"
        const val EXTRA_HEX                    = "hex"
        const val EXTRA_VERBOSE                = "on"

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
