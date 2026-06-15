package com.emberheat.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.emberheat.ble.RawFrame
import com.emberheat.di.ServiceLocator

class DebugBoxViewModel : ViewModel() {

    private val buffer = ArrayDeque<RawFrame>(MAX)

    private val _frames = MutableStateFlow<List<RawFrame>>(emptyList())
    val frames: StateFlow<List<RawFrame>> = _frames.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    init {
        viewModelScope.launch {
            ServiceLocator.ble.frames.collect { frame ->
                if (_paused.value) return@collect
                buffer.addLast(frame)
                while (buffer.size > MAX) buffer.removeFirst()
                _frames.value = buffer.toList()
            }
        }
    }

    fun togglePause() { _paused.value = !_paused.value }
    fun clear() {
        buffer.clear()
        _frames.value = emptyList()
    }

    private companion object { const val MAX = 256 }
}
