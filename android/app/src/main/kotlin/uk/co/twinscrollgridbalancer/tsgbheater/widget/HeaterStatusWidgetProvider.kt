package uk.co.twinscrollgridbalancer.tsgbheater.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// Read-only 2x1 widget: temp + heater name + connection state. No buttons —
// tapping opens the app. All providers share HeaterWidgetUpdater, so a
// single push from HeaterService refreshes every variant the user has on
// their home screen.
class HeaterStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }

    override fun onEnabled(ctx: Context) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }
}
