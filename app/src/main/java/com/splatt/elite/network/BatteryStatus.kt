package com.splatt.elite.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BatteryStatus {
    private val _percent = MutableStateFlow(-1)
    val percent: StateFlow<Int> = _percent.asStateFlow()

    internal fun update(value: Int) {
        _percent.value = value.coerceIn(-1, 100)
    }
}
