package uk.co.twinscrollgridbalancer.tsgbheater.di

import android.content.Context
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.data.auto.AutoStartStopController
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.GroupController
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.GroupStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppSettingsStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore

// Manual DI. Kept tiny and explicit on purpose — Hilt-level ceremony has
// no payoff at this size. Init from TsgbHeaterApp.onCreate() before any UI runs.
object ServiceLocator {

    private var initialised = false

    lateinit var ble:          BleManager               private set
    lateinit var boundDevices: BoundDeviceStore         private set
    lateinit var settings:     AppSettingsStore         private set
    lateinit var groups:       GroupStore               private set
    lateinit var auto:         AutoStartStopController  private set
    lateinit var groupCtl:     GroupController          private set

    fun init(ctx: Context) {
        if (initialised) return
        val app = ctx.applicationContext
        ble          = BleManager(app)
        boundDevices = BoundDeviceStore(app)
        settings     = AppSettingsStore(app)
        groups       = GroupStore(app)
        auto         = AutoStartStopController(ble, settings, boundDevices)
        groupCtl     = GroupController(app, ble, boundDevices)
        auto.start()
        initialised = true
    }
}
