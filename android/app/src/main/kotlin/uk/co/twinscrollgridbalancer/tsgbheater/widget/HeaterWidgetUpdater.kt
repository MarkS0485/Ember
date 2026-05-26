package uk.co.twinscrollgridbalancer.tsgbheater.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import uk.co.twinscrollgridbalancer.tsgbheater.MainActivity
import uk.co.twinscrollgridbalancer.tsgbheater.R
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.service.HeaterService

// Single source of truth for all four home-screen widget variants. The
// HeaterService keeps the snapshot fresh as BLE state changes; provider
// callbacks (add / reboot / locale change) trigger a redraw and ensure
// the service is alive. Each variant is described by [Variant] — adding a
// fifth widget is just a new entry + a new layout.
object HeaterWidgetUpdater {

    // What the widget actually renders, kept off the BLE hot path so
    // AppWidgetProvider callbacks (main thread) never block on DataStore.
    data class Snapshot(
        val mac: String?         = null,
        val name: String?        = null,
        val tempC: Double?       = null,
        val connection: ConnectionState = ConnectionState.Idle,
        val runningMode: RunningMode    = RunningMode.Unknown,
    )

    enum class Variant(
        val providerClass: Class<*>,
        val layoutRes: Int,
        val hasHeaterButtons: Boolean,
        val hasBlowerButtons: Boolean,
    ) {
        Combo(
            HeaterWidgetProvider::class.java,
            R.layout.widget_heater,
            hasHeaterButtons = true,
            hasBlowerButtons = true,
        ),
        Status(
            HeaterStatusWidgetProvider::class.java,
            R.layout.widget_status,
            hasHeaterButtons = false,
            hasBlowerButtons = false,
        ),
        HeaterOnly(
            HeaterControlWidgetProvider::class.java,
            R.layout.widget_heater_only,
            hasHeaterButtons = true,
            hasBlowerButtons = false,
        ),
        BlowerOnly(
            BlowerControlWidgetProvider::class.java,
            R.layout.widget_blower_only,
            hasHeaterButtons = false,
            hasBlowerButtons = true,
        ),
    }

    @Volatile private var snapshot: Snapshot = Snapshot()

    // --- Public push entry points -------------------------------------

    fun setBinding(ctx: Context, mac: String?, name: String?) {
        val next = snapshot.copy(mac = mac, name = name)
        if (next == snapshot) return
        snapshot = next
        pushAll(ctx)
    }

    // Telemetry arrives at ~1 Hz; dedupe on the fields we actually render
    // so we don't IPC the launcher every second when only e.g. fan-rpm
    // changed under the surface.
    //
    // Temp source mirrors the DeviceScreen hero card: prefer ambientTempC
    // (always reported) and fall back to outletTempC for hardware that
    // populates the outlet sensor. This heater family reports the
    // NOT_SUPPORTED sentinel for outletTemp so reading it directly leaves
    // the widget stuck on "— °C".
    fun setLive(ctx: Context, telemetry: HeaterTelemetry?, state: ConnectionState) {
        val temp = telemetry?.ambientTempC ?: telemetry?.outletTempC
        val next = snapshot.copy(
            tempC       = temp,
            connection  = state,
            runningMode = telemetry?.runningMode ?: RunningMode.Unknown,
        )
        if (next.tempC == snapshot.tempC &&
            next.connection == snapshot.connection &&
            next.runningMode == snapshot.runningMode) {
            return
        }
        snapshot = next
        pushAll(ctx)
    }

    // Called by every AppWidgetProvider variant's OS-driven callbacks
    // (add, reboot, locale change, host-forced refresh). Reads the bound
    // MAC once and redraws from the in-memory snapshot.
    //
    // Deliberately does NOT call HeaterService.connect — the OS can fire
    // APPWIDGET_UPDATE for each registered provider in rapid succession,
    // and routing each one to ble.connect() tears down the live GATT
    // session four times in a row, which wedges the heater's BLE radio.
    // The bind flow (MainActivity / BindViewModel) owns service lifecycle;
    // the widget is a passive viewer + click forwarder.
    fun onProviderRefresh(ctx: Context) {
        if (!hasAnyWidgets(ctx)) return
        val app = ctx.applicationContext
        val (mac, name) = runBlocking {
            val store = ServiceLocator.boundDevices
            val m = store.currentMac.first()
            val n = m?.let { id -> store.all.first().firstOrNull { it.mac == id }?.name }
            m to n
        }
        snapshot = snapshot.copy(mac = mac, name = name)
        pushAll(app)
    }

    // --- Rendering ----------------------------------------------------

    private fun pushAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        Variant.entries.forEach { variant ->
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, variant.providerClass))
            if (ids.isEmpty()) return@forEach
            mgr.updateAppWidget(ids, buildViews(ctx, variant, snapshot))
        }
    }

    private fun hasAnyWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return false
        return Variant.entries.any {
            mgr.getAppWidgetIds(ComponentName(ctx, it.providerClass)).isNotEmpty()
        }
    }

    private fun buildViews(ctx: Context, variant: Variant, s: Snapshot): RemoteViews {
        val v = RemoteViews(ctx.packageName, variant.layoutRes)

        v.setTextViewText(R.id.widget_temp, formatTemp(s))
        v.setTextViewText(R.id.widget_name, formatName(s))
        v.setOnClickPendingIntent(R.id.widget_temp, openAppIntent(ctx))
        v.setOnClickPendingIntent(R.id.widget_name, openAppIntent(ctx))

        val bound = s.mac != null
        if (variant.hasHeaterButtons) {
            v.setBoolean(R.id.widget_heater_on,  "setEnabled", bound)
            v.setBoolean(R.id.widget_heater_off, "setEnabled", bound)
            // Light up the ON button when the heater reports an active
            // running mode; OFF is always slate so the resting state is
            // "all grey" — no visual clutter when nothing's burning.
            val heaterActive = s.runningMode in HEATER_ACTIVE_MODES
            v.setInt(R.id.widget_heater_on, "setBackgroundResource",
                if (heaterActive) R.drawable.widget_btn_red else R.drawable.widget_btn_slate)
            v.setInt(R.id.widget_heater_off, "setBackgroundResource",
                R.drawable.widget_btn_slate)
            v.setOnClickPendingIntent(
                R.id.widget_heater_on,
                serviceIntent(ctx, HeaterService.ACTION_WIDGET_HEATER_ON,  REQ_HEATER_ON),
            )
            v.setOnClickPendingIntent(
                R.id.widget_heater_off,
                serviceIntent(ctx, HeaterService.ACTION_WIDGET_HEATER_OFF, REQ_HEATER_OFF),
            )
        }
        if (variant.hasBlowerButtons) {
            v.setBoolean(R.id.widget_blower_on,  "setEnabled", bound)
            v.setBoolean(R.id.widget_blower_off, "setEnabled", bound)
            val blowerActive = s.runningMode == RunningMode.Ventilation
            v.setInt(R.id.widget_blower_on, "setBackgroundResource",
                if (blowerActive) R.drawable.widget_btn_blue else R.drawable.widget_btn_slate)
            v.setInt(R.id.widget_blower_off, "setBackgroundResource",
                R.drawable.widget_btn_slate)
            v.setOnClickPendingIntent(
                R.id.widget_blower_on,
                serviceIntent(ctx, HeaterService.ACTION_WIDGET_BLOWER_ON,  REQ_BLOWER_ON),
            )
            v.setOnClickPendingIntent(
                R.id.widget_blower_off,
                serviceIntent(ctx, HeaterService.ACTION_WIDGET_BLOWER_OFF, REQ_BLOWER_OFF),
            )
        }
        return v
    }

    private val HEATER_ACTIVE_MODES = setOf(
        RunningMode.Ignition,
        RunningMode.AutoRun,
        RunningMode.ManualRun,
        RunningMode.StartStopActive,
    )

    private fun formatTemp(s: Snapshot): String {
        val t = s.tempC ?: return "— °C"
        return "%.1f °C".format(t)
    }

    private fun formatName(s: Snapshot): String {
        if (s.mac == null) return "No heater paired"
        val base = s.name ?: s.mac
        return when (s.connection) {
            ConnectionState.Ready                -> base
            ConnectionState.Idle                 -> "$base · offline"
            ConnectionState.Failed               -> "$base · failed"
            ConnectionState.Scanning,
            ConnectionState.Connecting,
            ConnectionState.DiscoveringServices,
            ConnectionState.Reconnecting         -> "$base · connecting"
        }
    }

    // --- Intents ------------------------------------------------------

    private fun openAppIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            ctx, REQ_OPEN_APP, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val i = Intent(ctx, HeaterService::class.java).apply { this.action = action }
        return PendingIntent.getForegroundService(
            ctx, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Distinct request codes so PendingIntents don't collapse across the
    // four widget variants (FLAG_UPDATE_CURRENT keys on action+request).
    private const val REQ_OPEN_APP   = 100
    private const val REQ_HEATER_ON  = 201
    private const val REQ_HEATER_OFF = 202
    private const val REQ_BLOWER_ON  = 203
    private const val REQ_BLOWER_OFF = 204
}
