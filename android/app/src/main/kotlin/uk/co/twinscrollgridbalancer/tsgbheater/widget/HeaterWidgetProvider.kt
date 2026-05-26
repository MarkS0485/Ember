package uk.co.twinscrollgridbalancer.tsgbheater.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// Home-screen widget for the last-paired heater. Live state is pushed from
// HeaterService via HeaterWidgetUpdater; the provider's own callbacks only
// fire for OS-level events (add/remove/reboot/locale-change) so they just
// trigger a redraw from the cached snapshot and kick the service awake.
class HeaterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }

    override fun onEnabled(ctx: Context) {
        HeaterWidgetUpdater.onProviderRefresh(ctx)
    }
}
