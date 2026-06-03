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
import kotlinx.coroutines.Job
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
        private var commandWriteInFlight = false
        private val pendingNotifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

        @Volatile private var commandSequence: Int = 0

        @Volatile private var autoReconnectDevice: BluetoothDevice? = null
        private var reconnectJob: Job? = null

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
                    if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_DISCONNECTED) {
                        handleGattFailure(gatt, "connection failed")
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            _connectionState.value = "discovering"
                            if (!gatt.discoverServices()) {
                                handleGattFailure(gatt, "service discovery start failed")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val ownedGatt =
                                synchronized(this@GooseBLEManager) {
                                    if (activeGatt === gatt) {
                                        activeGatt = null
                                        commandCharacteristic = null
                                        commandWriteInFlight = false
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
                    if (notifyChars.isEmpty()) {
                        _connectionState.value = "notification characteristic not found"
                        return
                    }
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
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        synchronized(this@GooseBLEManager) { pendingNotifyQueue.clear() }
                        _connectionState.value = "notification enable failed"
                        return
                    }
                    enableNextNotification(gatt)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleCharacteristicChanged(characteristic.uuid, value)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    val ownedWrite =
                        synchronized(this@GooseBLEManager) {
                        if (activeGatt === gatt && commandCharacteristic === characteristic) {
                            commandWriteInFlight = false
                            true
                        } else {
                            false
                        }
                    }
                    if (ownedWrite && status != BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = "command write failed"
                    }
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
                reconnectJob?.cancel()
                reconnectJob = null
                activeGatt?.let { gatt ->
                    gatt.disconnect()
                    gatt.close()
                    activeGatt = null
                    commandCharacteristic = null
                    commandWriteInFlight = false
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
            synchronized(this) {
                reconnectJob?.cancel()
                reconnectJob = null
            }
            val gatt =
                synchronized(this) {
                    val g = activeGatt
                    activeGatt = null
                    commandCharacteristic = null
                    commandWriteInFlight = false
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
            val writeRequest =
                synchronized(this) {
                    if (commandWriteInFlight) return false
                    val gatt = activeGatt ?: return false
                    val char = commandCharacteristic ?: return false
                    val sequence = commandSequence.toByte()
                    commandSequence = (commandSequence + 1) and 0xff
                    commandWriteInFlight = true
                    CommandWriteRequest(gatt, char, sequence)
                }
            val frame = WhoopCommandBuilder.build(command, writeRequest.sequence)

            val writeStarted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    writeRequest.gatt.writeCharacteristic(
                        writeRequest.characteristic,
                        frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    writeRequest.characteristic.value = frame
                    writeRequest.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    writeRequest.gatt.writeCharacteristic(writeRequest.characteristic)
                }
            if (!writeStarted) {
                synchronized(this) { commandWriteInFlight = false }
            }
            return writeStarted
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
        private fun onAllNotificationsEnabled() {
            _connectionState.value = "ready"
            // Start real-time HR streaming — band won't send HR until this toggle.
            // Ported from iOS GooseBLEClient.swift SensorStreamCommandKind.TOGGLE_REALTIME_HR_ON.
            managerScope.launch {
                delay(STARTUP_COMMAND_DELAY_MS)
                sendCommand(WhoopCommand.ToggleRealtimeHROn)
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun enableNextNotification(gatt: BluetoothGatt) {
            val characteristic = synchronized(this) { pendingNotifyQueue.removeFirstOrNull() }
            if (characteristic == null) {
                onAllNotificationsEnabled()
                return
            }
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                synchronized(this) { pendingNotifyQueue.clear() }
                _connectionState.value = "notification enable failed"
                return
            }
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                // No CCCD on this characteristic — skip and continue
                enableNextNotification(gatt)
                return
            }
            val writeStarted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            if (!writeStarted) {
                synchronized(this) { pendingNotifyQueue.clear() }
                _connectionState.value = "notification enable failed"
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

        private data class CommandWriteRequest(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val sequence: Byte,
        )

        private fun scheduleReconnect(device: BluetoothDevice) {
            synchronized(this) {
                reconnectJob?.cancel()
                reconnectJob =
                    managerScope.launch {
                        delay(RECONNECT_DELAY_MS)
                        val shouldReconnect =
                            _connectionState.value == "disconnected" &&
                                autoReconnectDevice?.address == device.address
                        if (shouldReconnect) {
                            connect(device)
                        }
                    }
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleGattFailure(
            gatt: BluetoothGatt,
            state: String,
        ) {
            val ownedGatt =
                synchronized(this) {
                    if (activeGatt === gatt) {
                        activeGatt = null
                        commandCharacteristic = null
                        commandWriteInFlight = false
                        pendingNotifyQueue.clear()
                        true
                    } else {
                        false
                    }
                }
            gatt.close()
            if (ownedGatt) {
                _connectionState.value = state
                autoReconnectDevice?.let { scheduleReconnect(it) }
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

            private const val STARTUP_COMMAND_DELAY_MS = 200L
            private const val RECONNECT_DELAY_MS = 3_000L
        }
    }
