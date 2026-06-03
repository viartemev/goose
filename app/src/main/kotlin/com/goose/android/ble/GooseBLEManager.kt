package com.goose.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GooseBLEManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val bluetoothManager: BluetoothManager =
            context.getSystemService(BluetoothManager::class.java)

        private val _connectionState = MutableStateFlow("disconnected")
        val connectionState: StateFlow<String> = _connectionState.asStateFlow()

        // Tracks the active scan so stopScan() can cancel it imperatively.
        // callbackFlow's awaitClose handles cleanup on coroutine cancellation.
        private var activeScanner: android.bluetooth.le.BluetoothLeScanner? = null
        private var activeScanCallback: ScanCallback? = null

        fun hasRequiredPermissions(): Boolean =
            REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }

        /** Emits [ScanResult]s for WHOOP devices filtered by service UUID.
         *  Cancelling the collector automatically stops the scan. */
        @SuppressLint("MissingPermission")
        fun scanFlow(): Flow<ScanResult> =
            callbackFlow {
                val scanner =
                    bluetoothManager.adapter?.bluetoothLeScanner
                        ?: throw IllegalStateException("BLE scanner not available")

                val filters =
                    WHOOP_SERVICE_UUIDS.map { uuid ->
                        ScanFilter
                            .Builder()
                            .setServiceUuid(ParcelUuid.fromString(uuid))
                            .build()
                    }
                val settings =
                    ScanSettings
                        .Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()

                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(
                            callbackType: Int,
                            result: ScanResult,
                        ) {
                            trySend(result)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            close(CancellationException("BLE scan failed: errorCode=$errorCode"))
                        }
                    }

                activeScanner = scanner
                activeScanCallback = callback
                scanner.startScan(filters, settings, callback)

                awaitClose {
                    scanner.stopScan(callback)
                    if (activeScanner === scanner) {
                        activeScanner = null
                        activeScanCallback = null
                    }
                }
            }

        /** Stops an active scan started via [scanFlow]. Prefer cancelling the collector instead. */
        @SuppressLint("MissingPermission")
        fun stopScan() {
            val scanner = activeScanner ?: return
            val callback = activeScanCallback ?: return
            scanner.stopScan(callback)
            activeScanner = null
            activeScanCallback = null
        }

        companion object {
            val REQUIRED_PERMISSIONS: Array<String> =
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )

            /** WHOOP service UUIDs used as BLE scan filter. Gen5 = fd4b, Gen4 = 6108. */
            val WHOOP_SERVICE_UUIDS: List<String> =
                listOf(
                    "fd4b0001-cce1-4033-93ce-002d5875f58a",
                    "61080001-8d6d-82b8-614a-1c8cb0f8dcc6",
                )
        }
    }
