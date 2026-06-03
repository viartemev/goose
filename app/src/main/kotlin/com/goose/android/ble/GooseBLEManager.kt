package com.goose.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.goose.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val STANDARD_HR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

@Singleton
class GooseBLEManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val managerScope: CoroutineScope,
    ) {
        private val bluetoothManager: BluetoothManager =
            context.getSystemService(BluetoothManager::class.java)

        private val _connectionState = MutableStateFlow("disconnected")
        val connectionState: StateFlow<String> = _connectionState.asStateFlow()

        private val notificationPipeline = NotificationPipeline(managerScope)
        private val vitalsProcessor = VitalsProcessor()

        /** Parsed WHOOP frames from incoming BLE notifications. */
        val frames: SharedFlow<WhoopFrame> get() = notificationPipeline.frames

        /** Notification chunks dropped due to back-pressure (best-effort). */
        val droppedNotificationCount: Long get() = notificationPipeline.droppedNotificationCount

        /** Latest valid heart rate from the standard BLE HR characteristic (bpm). */
        val liveHeartRate: StateFlow<Int?> get() = vitalsProcessor.liveHeartRate

        /** Latest HRV RMSSD estimate derived from RR intervals (ms). */
        val liveHRV: StateFlow<Double?> get() = vitalsProcessor.liveHRV

        /** Resting HR estimate — low-quartile mean of the recent HR window (bpm). */
        val restingHeartRate: StateFlow<Double?> get() = vitalsProcessor.restingHeartRate

        /** Closes the notification pipeline's channel, allowing the consumer coroutine to exit. */
        fun closePipeline() {
            notificationPipeline.close()
        }

        // Guarded by `this` — accessed from both GATT binder thread and callers
        private var activeGatt: BluetoothGatt? = null
        private var commandCharacteristic: BluetoothGattCharacteristic? = null
        private val pendingNotifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

        @Volatile private var commandSequence: Byte = 0

        @Volatile private var autoReconnectDevice: BluetoothDevice? = null

        // Scan state — accessed from scan coroutines
        private var activeScanner: android.bluetooth.le.BluetoothLeScanner? = null
        private var activeScanCallback: ScanCallback? = null

        private val gattCallback =
            object : BluetoothGattCallback() {
                @SuppressLint("MissingPermission")
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            _connectionState.value = "discovering"
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val ownedGatt =
                                synchronized(this@GooseBLEManager) {
                                    if (activeGatt === gatt) {
                                        activeGatt = null
                                        commandCharacteristic = null
                                        pendingNotifyQueue.clear()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            gatt.close()
                            if (ownedGatt) {
                                _connectionState.value = "disconnected"
                                val reconnect = autoReconnectDevice
                                // Reconnect on unexpected disconnect (status != GATT_SUCCESS means error)
                                if (reconnect != null && status != BluetoothGatt.GATT_SUCCESS) {
                                    scheduleReconnect(reconnect)
                                }
                            }
                        }
                    }
                }

                @SuppressLint("MissingPermission")
                override fun onServicesDiscovered(
                    gatt: BluetoothGatt,
                    status: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = "service discovery failed"
                        return
                    }

                    val commandChar = findCommandCharacteristic(gatt)
                    if (commandChar == null) {
                        _connectionState.value = "characteristic not found"
                        return
                    }

                    val notifyChars = findNotificationCharacteristics(gatt)
                    synchronized(this@GooseBLEManager) {
                        commandCharacteristic = commandChar
                        pendingNotifyQueue.clear()
                        pendingNotifyQueue.addAll(notifyChars)
                    }
                    enableNextNotification(gatt)
                }

                @SuppressLint("MissingPermission")
                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    enableNextNotification(gatt)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleCharacteristicChanged(characteristic.uuid, value)
                }

                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { value ->
                        handleCharacteristicChanged(characteristic.uuid, value)
                    }
                }
            }

        fun hasRequiredPermissions(): Boolean =
            REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }

        /** Connects to [device] and starts GATT service discovery. */
        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice) {
            synchronized(this) {
                activeGatt?.let { gatt ->
                    gatt.disconnect()
                    gatt.close()
                    activeGatt = null
                    commandCharacteristic = null
                    pendingNotifyQueue.clear()
                }
            }
            notificationPipeline.reset()
            vitalsProcessor.reset()
            autoReconnectDevice = device
            _connectionState.value = "connecting"
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            synchronized(this) { activeGatt = gatt }
        }

        /** Disconnects and clears the remembered device, suppressing auto-reconnect. */
        @SuppressLint("MissingPermission")
        fun disconnect() {
            autoReconnectDevice = null
            val gatt =
                synchronized(this) {
                    val g = activeGatt
                    activeGatt = null
                    commandCharacteristic = null
                    pendingNotifyQueue.clear()
                    g
                }
            _connectionState.value = "disconnected"
            gatt?.disconnect()
            gatt?.close()
        }

        /** Emits [ScanResult]s for WHOOP devices filtered by service UUID. */
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

        /**
         * Sends [command] to the connected WHOOP band.
         * Returns true if the write was dispatched, false if not connected or no command characteristic.
         */
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        fun sendCommand(command: WhoopCommand): Boolean {
            val (gatt, char) = synchronized(this) { activeGatt to commandCharacteristic }
            if (gatt == null || char == null) return false

            val seq =
                synchronized(this) {
                    val s = commandSequence
                    commandSequence = if (s == Byte.MAX_VALUE) 0 else (s + 1).toByte()
                    s
                }
            val frame = WhoopCommandBuilder.build(command, seq)

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                char.value = frame
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(char)
            }
        }

        /** Stops an active scan started via [scanFlow]. */
        @SuppressLint("MissingPermission")
        fun stopScan() {
            val scanner = activeScanner ?: return
            val callback = activeScanCallback ?: return
            scanner.stopScan(callback)
            activeScanner = null
            activeScanCallback = null
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun enableNextNotification(gatt: BluetoothGatt) {
            val characteristic = synchronized(this) { pendingNotifyQueue.removeFirstOrNull() }
            if (characteristic == null) {
                _connectionState.value = "ready"
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                // No CCCD on this characteristic — skip and continue
                enableNextNotification(gatt)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        private fun findCommandCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? =
            gatt.services
                .flatMap { it.characteristics ?: emptyList() }
                .firstOrNull { c -> COMMAND_CHARACTERISTIC_UUIDS.any { UUID.fromString(it) == c.uuid } }

        private fun findNotificationCharacteristics(gatt: BluetoothGatt): List<BluetoothGattCharacteristic> {
            val allChars = gatt.services.flatMap { it.characteristics ?: emptyList() }
            val whoopChars =
                allChars.filter { c ->
                    NOTIFICATION_CHARACTERISTIC_UUIDS.any { UUID.fromString(it) == c.uuid }
                }
            val standardHRChar = allChars.firstOrNull { it.uuid == STANDARD_HR_UUID }
            return if (standardHRChar != null) whoopChars + standardHRChar else whoopChars
        }

        private fun handleCharacteristicChanged(
            uuid: UUID,
            value: ByteArray,
        ) {
            if (uuid == STANDARD_HR_UUID) {
                val measurement = VitalsProcessor.parseStandardHRMeasurement(value) ?: return
                vitalsProcessor.processHeartRate(measurement.first)
                vitalsProcessor.processRRIntervals(measurement.second)
            } else {
                notificationPipeline.push(value)
            }
        }

        private fun scheduleReconnect(device: BluetoothDevice) {
            managerScope.launch {
                delay(RECONNECT_DELAY_MS)
                if (_connectionState.value == "disconnected" && autoReconnectDevice != null) {
                    connect(device)
                }
            }
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

            /** Command write characteristic UUIDs (one per generation). */
            val COMMAND_CHARACTERISTIC_UUIDS: List<String> =
                listOf(
                    "fd4b0002-cce1-4033-93ce-002d5875f58a",
                    "61080002-8d6d-82b8-614a-1c8cb0f8dcc6",
                )

            /** Notification/indicate characteristic UUIDs — all gens. */
            val NOTIFICATION_CHARACTERISTIC_UUIDS: List<String> =
                listOf(
                    "fd4b0003-cce1-4033-93ce-002d5875f58a",
                    "fd4b0004-cce1-4033-93ce-002d5875f58a",
                    "fd4b0005-cce1-4033-93ce-002d5875f58a",
                    "fd4b0007-cce1-4033-93ce-002d5875f58a",
                    "61080003-8d6d-82b8-614a-1c8cb0f8dcc6",
                    "61080004-8d6d-82b8-614a-1c8cb0f8dcc6",
                    "61080005-8d6d-82b8-614a-1c8cb0f8dcc6",
                    "61080007-8d6d-82b8-614a-1c8cb0f8dcc6",
                )

            /** Client Characteristic Configuration Descriptor UUID (Bluetooth SIG standard). */
            val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

            private const val RECONNECT_DELAY_MS = 3_000L
        }
    }
