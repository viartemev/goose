package com.goose.android.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goose.android.ble.GooseBLEManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val bleManager: GooseBLEManager,
    ) : ViewModel() {
        val connectionState: StateFlow<String> = bleManager.connectionState
        val liveHeartRate: StateFlow<Int?> = bleManager.liveHeartRate

        private val _scannedDevices = MutableStateFlow<List<ScanResult>>(emptyList())
        val scannedDevices: StateFlow<List<ScanResult>> = _scannedDevices.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _connectedDeviceName = MutableStateFlow<String?>(null)
        val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

        private var scanJob: Job? = null

        fun startScan() {
            scanJob?.cancel()
            _scannedDevices.value = emptyList()
            _isScanning.value = true
            scanJob =
                viewModelScope.launch {
                    try {
                        bleManager.scanFlow().collect { result ->
                            val address = result.device.address
                            if (_scannedDevices.value.none { it.device.address == address }) {
                                _scannedDevices.value = _scannedDevices.value + result
                            }
                        }
                    } finally {
                        _isScanning.value = false
                    }
                }
        }

        fun stopScan() {
            scanJob?.cancel()
            _isScanning.value = false
        }

        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice) {
            stopScan()
            _connectedDeviceName.value = device.name ?: device.address
            bleManager.connect(device)
        }

        fun disconnect() {
            _connectedDeviceName.value = null
            bleManager.disconnect()
        }

        override fun onCleared() {
            super.onCleared()
            stopScan()
        }
    }
