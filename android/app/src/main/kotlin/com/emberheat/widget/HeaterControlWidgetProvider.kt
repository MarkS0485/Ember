package com.emberheat.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// 3x1 widget with just the heater on/off pair (plus temp readout). For
// users who don't want blower controls cluttering the home screen.
class HeaterControlWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }

    override fun onEnabled(ctx: Context) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }
}
