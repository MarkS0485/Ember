package com.emberheat.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// 3x1 widget with just the blower on/off pair (plus temp readout). The
// off action goes through CMD_OFF, not CMD_OIL_PUMP_OFF — see the
// "Manual pump run" note in docs/BLE_PROTOCOL.md.
class BlowerControlWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }

    override fun onEnabled(ctx: Context) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }
}
