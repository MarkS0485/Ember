package uk.co.twinscrollgridbalancer.tsgbheater.data.schedule

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppSettingsStore
import java.util.Calendar

// Drives the heater's 7-day timer table from a richer in-app schedule.
//
// The heater can only store one (on, off) pair per weekday; this
// controller picks the *next-relevant* event for each day and pushes the
// resulting 7-slot table whenever the choice changes (new event due to
// time-of-day rolling forward, or the user editing the schedule).
//
// All control happens in user-space — if the phone is off when an event
// boundary crosses, the slot doesn't rotate until the controller wakes
// again. The foreground HeaterService keeps the controller alive while
// scheduling is enabled.
class ScheduleController(
    private val ble:      BleManager,
    private val store:    ScheduleStore,
    private val settings: AppSettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    // Last successful push, signature-encoded so we can dedupe writes
    // without re-comparing every byte. Cleared on stop().
    @Volatile private var lastPushedSignature: String? = null

    // Tracks the previous reconcile's `enabled` flag so we can detect a
    // true→false transition and push a single "clear everything" frame
    // before going dark. Otherwise the heater would keep whatever was
    // last programmed forever.
    @Volatile private var wasEnabled: Boolean = false

    private val _status = MutableStateFlow<ScheduleStatus>(ScheduleStatus.Disabled)
    val status: StateFlow<ScheduleStatus> = _status.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                store.schedule,
                settings.scheduleModeEnabled,
                ble.connectionState,
                clockTickFlow(),
            ) { sched, enabled, state, tickNow ->
                Reconcile(sched, enabled, state, tickNow)
            }.collect { r -> reconcile(r) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastPushedSignature = null
        _status.value = ScheduleStatus.Disabled
    }

    // Push an explicit all-zero schedule. Used by the UI "Clear" button
    // for the case where the heater's slots got out of sync with the
    // app's view (e.g. the auto-clear-on-disable lost the race with the
    // link going down). Resets the signature so the next reconcile will
    // re-push the correct table if scheduling is still enabled.
    suspend fun clearHeater(): Boolean {
        if (ble.connectionState.value != ConnectionState.Ready) return false
        val ok = ble.writeTimer(emptySlots())
        if (ok) lastPushedSignature = null
        return ok
    }

    private fun emptySlots(): List<FrameCodec.WriteTimerSlot> =
        (0..6).map { FrameCodec.WriteTimerSlot(it, MODE_OFF, 0, 0, 0, 0) }

    private data class Reconcile(
        val schedule: Schedule,
        val enabled:  Boolean,
        val state:    ConnectionState,
        val now:      Long,
    )

    private suspend fun reconcile(r: Reconcile) {
        if (!r.enabled) {
            // On the enabled→disabled transition, wipe the heater's
            // slots once so the controller doesn't leave a stale
            // schedule running for ever. Best-effort: if the link is
            // down we just skip and the slots stay until the user hits
            // the manual Clear button (or re-enables and disables once
            // the link is back).
            if (wasEnabled && r.state == ConnectionState.Ready) {
                Log.i(TAG, "Schedule disabled — clearing heater slots")
                ble.writeTimer(emptySlots())
            }
            wasEnabled = false
            lastPushedSignature = null
            _status.value = ScheduleStatus.Disabled
            return
        }
        wasEnabled = true
        if (r.state != ConnectionState.Ready) {
            _status.value = ScheduleStatus.WaitingForLink(r.schedule.events.size)
            return
        }
        val cal = Calendar.getInstance().apply { timeInMillis = r.now }
        val slots = computeSlots(r.schedule, cal)
        val sig   = signature(slots)
        if (sig == lastPushedSignature) {
            _status.value = ScheduleStatus.Synced(
                eventCount       = r.schedule.events.size,
                lastPushedAtMs   = _status.value.lastPushedAtMs,
                nextEventSummary = describeNext(slots, cal),
            )
            return
        }
        Log.i(TAG, "Pushing schedule: $sig")
        val ok = ble.writeTimer(slots)
        if (ok) {
            lastPushedSignature = sig
            _status.value = ScheduleStatus.Synced(
                eventCount       = r.schedule.events.size,
                lastPushedAtMs   = System.currentTimeMillis(),
                nextEventSummary = describeNext(slots, cal),
            )
        } else {
            Log.w(TAG, "writeTimer failed; will retry on next tick")
            _status.value = ScheduleStatus.WriteFailed(r.schedule.events.size)
        }
    }

    // --- Slot computation ---------------------------------------------

    private fun computeSlots(sched: Schedule, now: Calendar): List<FrameCodec.WriteTimerSlot> {
        val todayDow = FrameCodec.calendarDayOfWeekToVendor(now.get(Calendar.DAY_OF_WEEK))
        val nowMin   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return (0..6).map { day ->
            val pick = pickEvent(sched.eventsOnDay(day), day, todayDow, nowMin)
            if (pick != null) {
                FrameCodec.WriteTimerSlot(
                    dayIndex = day,
                    modeRaw  = MODE_DAILY,
                    onHour   = pick.onHour,
                    onMin    = pick.onMin,
                    offHour  = pick.offHour,
                    offMin   = pick.offMin,
                )
            } else {
                FrameCodec.WriteTimerSlot(day, MODE_OFF, 0, 0, 0, 0)
            }
        }
    }

    // Pick the most-relevant event for a given weekday slot. For *today*
    // we prefer (a) an in-progress event so the heater keeps a consistent
    // off-time programmed, then (b) the next-upcoming event later today,
    // and only fall back to "next week's first event" if today is done.
    // For other weekdays, just the first chronological event.
    private fun pickEvent(
        events: List<ScheduleEvent>,
        day: Int,
        todayDow: Int,
        nowMin: Int,
    ): ScheduleEvent? {
        if (events.isEmpty()) return null
        if (day != todayDow) return events.first()
        events.firstOrNull { it.onMinuteOfDay <= nowMin && nowMin < it.offMinuteOfDay }
            ?.let { return it }
        events.firstOrNull { it.onMinuteOfDay > nowMin }
            ?.let { return it }
        return events.first()
    }

    private fun signature(slots: List<FrameCodec.WriteTimerSlot>): String =
        slots.joinToString("|") {
            "${it.dayIndex}:${it.modeRaw}/${it.onHour}:${it.onMin}->${it.offHour}:${it.offMin}"
        }

    private fun describeNext(
        slots: List<FrameCodec.WriteTimerSlot>,
        now: Calendar,
    ): String? {
        val todayDow = FrameCodec.calendarDayOfWeekToVendor(now.get(Calendar.DAY_OF_WEEK))
        val nowMin   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        // Walk weekdays starting today, find the first slot whose on-time
        // is in the future. (Today's "past" slots roll to next week.)
        for (offset in 0..6) {
            val day = (todayDow + offset) % 7
            val slot = slots.first { it.dayIndex == day }
            if (slot.modeRaw == MODE_OFF) continue
            val slotMin = slot.onHour * 60 + slot.onMin
            if (offset == 0 && slotMin <= nowMin) continue
            val dayLabel = DAY_LABELS[day]
            return "$dayLabel %02d:%02d → %02d:%02d".format(
                slot.onHour, slot.onMin, slot.offHour, slot.offMin
            )
        }
        return null
    }

    // 60-second tick. Cheap and good enough for minute-granularity
    // schedules. We could be smarter (sleep until the next event boundary)
    // but the de-dup means a tick with no change is a no-op IPC, not a
    // BLE write.
    private fun clockTickFlow(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG              = "ScheduleController"
        const val MODE_OFF         = 0
        const val MODE_DAILY       = 4
        const val TICK_INTERVAL_MS = 60_000L
        val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

sealed class ScheduleStatus(open val lastPushedAtMs: Long? = null) {
    object Disabled : ScheduleStatus()
    data class WaitingForLink(val eventCount: Int)         : ScheduleStatus()
    data class WriteFailed   (val eventCount: Int)         : ScheduleStatus()
    data class Synced(
        val eventCount: Int,
        override val lastPushedAtMs: Long?,
        val nextEventSummary: String?,
    ) : ScheduleStatus(lastPushedAtMs)
}
