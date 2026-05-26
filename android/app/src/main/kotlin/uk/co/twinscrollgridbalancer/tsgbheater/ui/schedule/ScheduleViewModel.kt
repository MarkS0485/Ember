package uk.co.twinscrollgridbalancer.tsgbheater.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.schedule.Schedule
import uk.co.twinscrollgridbalancer.tsgbheater.data.schedule.ScheduleEvent
import uk.co.twinscrollgridbalancer.tsgbheater.data.schedule.ScheduleStatus
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

data class ScheduleUiState(
    val schedule: Schedule        = Schedule(),
    val enabled:  Boolean         = false,
    val status:   ScheduleStatus  = ScheduleStatus.Disabled,
)

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val store    = ServiceLocator.scheduleStore
    private val settings = ServiceLocator.settings
    private val ctl      = ServiceLocator.scheduleCtl

    val ui: StateFlow<ScheduleUiState> = combine(
        store.schedule,
        settings.scheduleModeEnabled,
        ctl.status,
    ) { sched, enabled, status ->
        ScheduleUiState(sched, enabled, status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    fun setEnabled(v: Boolean) = viewModelScope.launch {
        settings.setScheduleModeEnabled(v)
    }

    fun addEvent(day: Int, onMinute: Int, offMinute: Int) = viewModelScope.launch {
        if (offMinute <= onMinute) return@launch
        store.mutate { it.add(ScheduleEvent(
            dayOfWeek      = day,
            onMinuteOfDay  = onMinute,
            offMinuteOfDay = offMinute,
        )) }
    }

    fun updateEvent(e: ScheduleEvent) = viewModelScope.launch {
        store.mutate { it.update(e) }
    }

    fun removeEvent(id: String) = viewModelScope.launch {
        store.mutate { it.remove(id) }
    }

    fun toggleEnabled(e: ScheduleEvent) = viewModelScope.launch {
        store.mutate { it.update(e.copy(enabled = !e.enabled)) }
    }

    fun clearHeaterNow() = viewModelScope.launch { ctl.clearHeater() }
}
