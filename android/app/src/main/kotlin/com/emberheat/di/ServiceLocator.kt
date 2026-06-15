package com.emberheat.di

import android.content.Context
import com.emberheat.billing.BillingManager
import com.emberheat.ble.BleManager
import com.emberheat.data.auto.AutoStartStopController
import com.emberheat.data.entitlement.EntitlementRepository
import com.emberheat.data.entitlement.EntitlementStore
import com.emberheat.data.entitlement.EntitlementWatcher
import com.emberheat.data.fuel.FuelStore
import com.emberheat.data.fuel.FuelTracker
import com.emberheat.data.group.GroupController
import com.emberheat.data.group.GroupStore
import com.emberheat.data.schedule.ScheduleController
import com.emberheat.data.schedule.ScheduleStore
import com.emberheat.data.store.AppSettingsStore
import com.emberheat.data.store.BoundDeviceStore
import com.emberheat.remote.PairedServerStore

// Manual DI. Kept tiny and explicit on purpose — Hilt-level ceremony has
// no payoff at this size. Init from EmberApp.onCreate() before any UI runs.
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
    lateinit var billing:      BillingManager           private set
    lateinit var entitlementStore: EntitlementStore     private set
    lateinit var entitlements: EntitlementRepository    private set
    lateinit var entitlementWatcher: EntitlementWatcher private set

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
        // Commercial entitlement built up-front: billing client + the
        // repository that decides whether Pro is active (purchase / sub /
        // legacy / trial). The repo's init runs the one-time grandfather
        // grant; connecting billing (below) kicks off product + ownership
        // queries. Built before the controllers so they can take its
        // isProActive flow as a gate.
        billing          = BillingManager(app)
        entitlementStore = EntitlementStore(app)
        entitlements     = EntitlementRepository(entitlementStore, billing)
        // Pro-gated runtime controllers. Each takes isProActive so a free
        // tier (or a lapsed trial) can't drive the heater.
        auto          = AutoStartStopController(ble, settings, boundDevices, entitlements.isProActive)
        groupCtl      = GroupController(app, ble, boundDevices, entitlements.isProActive)
        scheduleStore = ScheduleStore(app)
        scheduleCtl   = ScheduleController(ble, scheduleStore, settings, entitlements.isProActive)
        pairedServers = PairedServerStore(app)
        fuelStore     = FuelStore(app)
        fuelCtl       = FuelTracker(ble, boundDevices, fuelStore, entitlements.isProActive)
        // Warns (once) if Pro lapses while automation was actually in use.
        entitlementWatcher = EntitlementWatcher(app, entitlements, settings, boundDevices)
        billing.connect()
        auto.start()
        scheduleCtl.start()
        fuelCtl.start()
        entitlementWatcher.start()
        initialised = true
    }
}
