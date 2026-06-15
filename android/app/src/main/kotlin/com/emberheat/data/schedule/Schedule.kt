package com.emberheat.data.schedule

import kotlinx.serialization.Serializable
import java.util.UUID

// One scheduled on→off pulse. Stored richer-than-heater: any number of
// pulses per weekday. The ScheduleController collapses these down to the
// heater's single per-day slot at runtime.
//
// Day index follows the vendor's Mon=0..Sun=6 convention so it lines up
// with what the heater actually stores. Times are minutes-since-midnight
// (0..1439) to make sorting and "is now between on and off?" cheap.
// Same-day events only — if you want a pulse spanning midnight, split it
// into two events on consecutive days.
@Serializable
data class ScheduleEvent(
    val id:              String  = UUID.randomUUID().toString(),
    val dayOfWeek:       Int,                  // 0=Mon … 6=Sun
    val onMinuteOfDay:   Int,                  // 0..1439
    val offMinuteOfDay:  Int,                  // > onMinuteOfDay, ≤ 1439
    val enabled:         Boolean = true,
) {
    init {
        require(dayOfWeek in 0..6) { "dayOfWeek must be 0..6, got $dayOfWeek" }
        require(onMinuteOfDay in 0..1439) { "onMinuteOfDay out of range: $onMinuteOfDay" }
        require(offMinuteOfDay in 1..1439) { "offMinuteOfDay out of range: $offMinuteOfDay" }
        require(offMinuteOfDay > onMinuteOfDay) {
            "offMinuteOfDay must be > onMinuteOfDay; split cross-midnight events"
        }
    }

    val onHour: Int   get() = onMinuteOfDay / 60
    val onMin:  Int   get() = onMinuteOfDay % 60
    val offHour: Int  get() = offMinuteOfDay / 60
    val offMin:  Int  get() = offMinuteOfDay % 60
}

@Serializable
data class Schedule(
    val events: List<ScheduleEvent> = emptyList(),
) {
    fun eventsOnDay(day: Int): List<ScheduleEvent> =
        events.filter { it.enabled && it.dayOfWeek == day }.sortedBy { it.onMinuteOfDay }

    fun add(e: ScheduleEvent): Schedule = copy(events = events + e)
    fun remove(id: String): Schedule    = copy(events = events.filterNot { it.id == id })
    fun update(e: ScheduleEvent): Schedule =
        copy(events = events.map { if (it.id == e.id) e else it })
}
