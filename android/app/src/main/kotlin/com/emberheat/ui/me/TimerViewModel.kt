package com.emberheat.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.emberheat.ble.FrameCodec
import com.emberheat.ble.TimerSlot
import com.emberheat.di.ServiceLocator

data class TimerUiState(
    val slots: List<TimerSlot> = emptyList(),
    val refreshing: Boolean   = false,
    val lastUpdatedMs: Long?  = null,
)

class TimerViewModel : ViewModel() {

    private val ble = ServiceLocator.ble

    private val _ui = MutableStateFlow(TimerUiState())
    val ui: StateFlow<TimerUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            ble.frames.collect { f ->
                if (f.tx) return@collect
                val slots = FrameCodec.parseTimerInfo(f.bytes) ?: return@collect
                _ui.value = TimerUiState(
                    slots = slots,
                    refreshing = false,
                    lastUpdatedMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun refresh() {
        _ui.value = _ui.value.copy(refreshing = true)
        viewModelScope.launch {
            val ok = ble.readTimerInfo()
            if (!ok) _ui.value = _ui.value.copy(refreshing = false)
        }
    }
}
