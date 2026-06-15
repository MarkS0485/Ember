package com.emberheat.ui.advance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.emberheat.ble.FrameCodec
import com.emberheat.ble.HeaterTelemetry
import com.emberheat.di.ServiceLocator

class AdvanceViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = ServiceLocator.ble

    val telemetry: StateFlow<HeaterTelemetry?> = ble.telemetry

    fun setTargetTempC(c: Int) = viewModelScope.launch {
        ble.setTargetTemp(c, FrameCodec.TempUnit.Celsius)
    }

    fun setGear(g: Int) = viewModelScope.launch {
        ble.setGear(g)
    }

    fun setHysteresisC(diff: Int) = viewModelScope.launch {
        ble.setTempHysteresis(diff, FrameCodec.TempUnit.Celsius)
    }
}
