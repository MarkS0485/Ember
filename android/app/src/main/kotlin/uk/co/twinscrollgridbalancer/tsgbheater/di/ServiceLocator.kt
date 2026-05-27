package uk.co.twinscrollgridbalancer.tsgbheater.di

import android.content.Context
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.data.auto.AutoStartStopController
import uk.co.twinscrollgridbalancer.tsgbheater.data.fuel.FuelStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.fuel.FuelTracker
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.GroupController
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.GroupStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.schedule.ScheduleController
import uk.co.twinscrollgridbalancer.tsgbheater.data.schedule.ScheduleStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppSettingsStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore
import uk.co.twinscrollgridbalancer.tsgbheater.remote.PairedServerStore

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
    lateinit var scheduleStore: ScheduleStore           private set
    lateinit var scheduleCtl:  ScheduleController       private set
    lateinit var pairedServers: PairedServerStore       private set
    lateinit var fuelStore:    FuelStore                private set
    lateinit var fuelCtl:      FuelTracker              private set

    fun init(ctx: Context) {
        if (initialised) return
        val app = ctx.applicationContext
        ble           = BleManager(app)
        boundDevices  = BoundDeviceStore(app)
        // BleManager needs the bound-device store to look up which
        // protocol to use for a given MAC at connect time. Construction
        // order forbids passing it via constructor, so inject after.
        ble.attachBoundDevices(boundDevices)
        settings      = AppSettingsStore(app)
        groups        = GroupStore(app)
        auto          = AutoStartStopController(ble, settings, boundDevices)
        groupCtl      = GroupController(app, ble, boundDevices)
        scheduleStore = ScheduleStore(app)
        scheduleCtl   = ScheduleController(ble, scheduleStore, settings)
        pairedServers = PairedServerStore(app)
        fuelStore     = FuelStore(app)
        fuelCtl       = FuelTracker(ble, boundDevices, fuelStore)
        auto.start()
        scheduleCtl.start()
        fuelCtl.start()
        initialised = true
    }
}
