package com.goose.android.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class GooseBLEManagerTest {
    private lateinit var manager: GooseBLEManager
    private lateinit var managerScope: TestScope

    @Before
    fun setUp() {
        managerScope = TestScope(StandardTestDispatcher())
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(BluetoothManager::class.java) } returns mockk(relaxed = true)
        manager = GooseBLEManager(ctx, managerScope.backgroundScope)
    }

    @After
    fun tearDown() {
        managerScope.cancel()
        unmockkAll()
    }

    // ---- Constants — sanity checks that catch UUID/permission typos ----

    @Test
    fun whoopServiceUuids_containsGen5AndGen4() {
        assertTrue(GooseBLEManager.WHOOP_SERVICE_UUIDS.any { it.startsWith("fd4b") })
        assertTrue(GooseBLEManager.WHOOP_SERVICE_UUIDS.any { it.startsWith("6108") })
        GooseBLEManager.WHOOP_SERVICE_UUIDS.forEach { UUID.fromString(it) } // valid UUID format
    }

    @Test
    fun commandCharacteristicUuids_areValidAndPaired() {
        assertEquals(2, GooseBLEManager.COMMAND_CHARACTERISTIC_UUIDS.size)
        GooseBLEManager.COMMAND_CHARACTERISTIC_UUIDS.forEach { UUID.fromString(it) }
    }

    @Test
    fun notificationCharacteristicUuids_eightEntriesAllValid() {
        assertEquals(8, GooseBLEManager.NOTIFICATION_CHARACTERISTIC_UUIDS.size)
        GooseBLEManager.NOTIFICATION_CHARACTERISTIC_UUIDS.forEach { UUID.fromString(it) }
    }

    @Test
    fun cccdUuid_isStandardBluetoothSig() {
        assertEquals(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            GooseBLEManager.CCCD_UUID,
        )
    }

    @Test
    fun requiredPermissions_containsBothBlePermissions() {
        val perms = GooseBLEManager.REQUIRED_PERMISSIONS.toList()
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
    }

    // ---- Initial state ----

    @Test
    fun initialConnectionState_isDisconnected() {
        assertEquals("disconnected", manager.connectionState.value)
    }

    // ---- GATT: STATE_CONNECTED ----

    @Test
    fun onConnected_stateBecomesDiscovering() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        gattCallback().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertEquals("discovering", manager.connectionState.value)
    }

    @Test
    fun onConnected_callsDiscoverServices() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        gattCallback().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        verify { gatt.discoverServices() }
    }

    // ---- GATT: STATE_DISCONNECTED ----

    @Test
    fun onDisconnected_ownedGatt_stateBecomesDisconnected() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        setActiveGatt(gatt)
        gattCallback().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals("disconnected", manager.connectionState.value)
    }

    @Test
    fun onDisconnected_ownedGatt_closesGatt() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        setActiveGatt(gatt)
        gattCallback().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        verify { gatt.close() }
    }

    @Test
    fun onDisconnected_foreignGatt_doesNotChangeState() {
        val ownGatt = mockk<BluetoothGatt>(relaxed = true)
        val foreignGatt = mockk<BluetoothGatt>(relaxed = true)
        // Bring manager into "discovering" state with ownGatt as active
        setActiveGatt(ownGatt)
        gattCallback().onConnectionStateChange(ownGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertEquals("discovering", manager.connectionState.value)

        // Foreign gatt disconnect must not affect state — ownGatt is still active
        gattCallback().onConnectionStateChange(foreignGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals("discovering", manager.connectionState.value)
    }

    // ---- GATT: onServicesDiscovered ----

    @Test
    fun onServicesDiscovered_gattFailure_setsFailedState() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        assertEquals("service discovery failed", manager.connectionState.value)
    }

    @Test
    fun onServicesDiscovered_noWhoopCharacteristics_setsCharacteristicNotFound() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.characteristics } returns emptyList()
        every { gatt.services } returns listOf(service)
        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals("characteristic not found", manager.connectionState.value)
    }

    @Test
    fun onServicesDiscovered_commandCharOnly_noNotifyQueue_immediatelySetsReady() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val service = serviceWith(commandChar("fd4b0002-cce1-4033-93ce-002d5875f58a"))
        every { gatt.services } returns listOf(service)
        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals("ready", manager.connectionState.value)
    }

    // ---- GATT: notification enabling → ready ----

    @Test
    fun onDescriptorWrite_afterOneNotifyChar_setsReady() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val service =
            serviceWith(
                commandChar("fd4b0002-cce1-4033-93ce-002d5875f58a"),
                notifyCharWithCccd("fd4b0003-cce1-4033-93ce-002d5875f58a"),
            )
        every { gatt.services } returns listOf(service)

        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertNotEquals("ready", manager.connectionState.value) // still waiting for descriptor write

        gattCallback().onDescriptorWrite(gatt, mockk(relaxed = true), BluetoothGatt.GATT_SUCCESS)
        assertEquals("ready", manager.connectionState.value)
    }

    @Test
    fun onDescriptorWrite_twoNotifyChars_setsReadyAfterSecond() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val service =
            serviceWith(
                commandChar("fd4b0002-cce1-4033-93ce-002d5875f58a"),
                notifyCharWithCccd("fd4b0003-cce1-4033-93ce-002d5875f58a"),
                notifyCharWithCccd("fd4b0004-cce1-4033-93ce-002d5875f58a"),
            )
        every { gatt.services } returns listOf(service)

        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        gattCallback().onDescriptorWrite(gatt, mockk(relaxed = true), BluetoothGatt.GATT_SUCCESS)
        assertNotEquals("ready", manager.connectionState.value) // one more to go

        gattCallback().onDescriptorWrite(gatt, mockk(relaxed = true), BluetoothGatt.GATT_SUCCESS)
        assertEquals("ready", manager.connectionState.value)
    }

    @Test
    fun notifyCharWithoutCccd_skipped_andReadySetWithoutWaiting() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        // notifyChar has no CCCD descriptor → enableNextNotification skips it and calls itself recursively
        val charNoCccd = commandChar("fd4b0003-cce1-4033-93ce-002d5875f58a") // getDescriptor → null
        val service = serviceWith(commandChar("fd4b0002-cce1-4033-93ce-002d5875f58a"), charNoCccd)
        every { gatt.services } returns listOf(service)

        gattCallback().onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        // No descriptor write needed → state immediately "ready"
        assertEquals("ready", manager.connectionState.value)
    }

    // ---- notification pipeline integration ----

    @Test
    fun onCharacteristicChanged_validFrame_emittedToFramesFlow() =
        managerScope.runTest {
            val collected = mutableListOf<WhoopFrame>()
            val job = launch { manager.frames.collect { collected.add(it) } }
            advanceUntilIdle() // start consumer + collector, both suspend waiting

            val gatt = mockk<BluetoothGatt>(relaxed = true)
            val char = mockk<BluetoothGattCharacteristic>(relaxed = true)
            gattCallback().onCharacteristicChanged(gatt, char, "aa0108000001e67123019101363e5c8d".hexToBytes())
            advanceUntilIdle()

            assertEquals(1, collected.size)
            assertTrue(collected[0] is WhoopFrame.Command)

            job.cancelAndJoin()
        }

    @Test
    fun onCharacteristicChanged_splitFrame_reassemblesAndEmits() =
        managerScope.runTest {
            val collected = mutableListOf<WhoopFrame>()
            val job = launch { manager.frames.collect { collected.add(it) } }
            advanceUntilIdle()

            val gatt = mockk<BluetoothGatt>(relaxed = true)
            val char = mockk<BluetoothGattCharacteristic>(relaxed = true)
            val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()
            val mid = bytes.size / 2

            gattCallback().onCharacteristicChanged(gatt, char, bytes.sliceArray(0 until mid))
            advanceUntilIdle()
            assertEquals(0, collected.size)

            gattCallback().onCharacteristicChanged(gatt, char, bytes.sliceArray(mid until bytes.size))
            advanceUntilIdle()
            assertEquals(1, collected.size)

            job.cancelAndJoin()
        }

    @Test
    fun connect_resetsNotificationPipeline() =
        managerScope.runTest {
            val collected = mutableListOf<WhoopFrame>()
            val job = launch { manager.frames.collect { collected.add(it) } }
            advanceUntilIdle()

            val gatt = mockk<BluetoothGatt>(relaxed = true)
            val char = mockk<BluetoothGattCharacteristic>(relaxed = true)
            val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()

            // Push first half — accumulates in parser buffer
            gattCallback().onCharacteristicChanged(gatt, char, bytes.sliceArray(0 until bytes.size / 2))
            advanceUntilIdle()
            assertEquals(0, collected.size)

            // connect() resets the pipeline — stale partial bytes discarded
            val device = mockk<android.bluetooth.BluetoothDevice>(relaxed = true)
            every { device.connectGatt(any(), any(), any(), any()) } returns mockk(relaxed = true)
            manager.connect(device)

            // Full frame after reset — must emit exactly 1 (no corruption from prior partial)
            gattCallback().onCharacteristicChanged(gatt, char, bytes)
            advanceUntilIdle()
            assertEquals(1, collected.size)

            job.cancelAndJoin()
        }

    // ---- disconnect() ----

    @Test
    fun disconnect_setsStateToDisconnected() {
        setActiveGatt(mockk(relaxed = true))
        manager.disconnect()
        assertEquals("disconnected", manager.connectionState.value)
    }

    @Test
    fun disconnect_clearsAutoReconnectDevice() {
        setActiveGatt(mockk(relaxed = true))
        setAutoReconnectDevice(mockk(relaxed = true))
        manager.disconnect()
        assertNull(getAutoReconnectDevice())
    }

    @Test
    fun disconnect_thenGattCallbackFires_noReconnectScheduled() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        setActiveGatt(gatt)
        setAutoReconnectDevice(mockk(relaxed = true))

        manager.disconnect() // clears activeGatt and autoReconnectDevice

        // Simulate callback from the now-orphaned gatt (activeGatt !== gatt → ownedGatt = false)
        gattCallback().onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED) // GATT_INTERNAL_ERROR

        assertNull(getAutoReconnectDevice()) // still null — no reconnect was scheduled
    }

    // ---- Reflection helpers ----

    private fun gattCallback(): BluetoothGattCallback {
        val field = GooseBLEManager::class.java.getDeclaredField("gattCallback")
        field.isAccessible = true
        return field.get(manager) as BluetoothGattCallback
    }

    private fun setActiveGatt(gatt: BluetoothGatt) {
        val field = GooseBLEManager::class.java.getDeclaredField("activeGatt")
        field.isAccessible = true
        field.set(manager, gatt)
    }

    private fun setAutoReconnectDevice(device: android.bluetooth.BluetoothDevice) {
        val field = GooseBLEManager::class.java.getDeclaredField("autoReconnectDevice")
        field.isAccessible = true
        field.set(manager, device)
    }

    private fun getAutoReconnectDevice(): android.bluetooth.BluetoothDevice? {
        val field = GooseBLEManager::class.java.getDeclaredField("autoReconnectDevice")
        field.isAccessible = true
        return field.get(manager) as? android.bluetooth.BluetoothDevice
    }

    // ---- Mock builder helpers ----

    // ---- Hex helper ----

    private fun String.hexToBytes(): ByteArray {
        val cleaned = replace(" ", "").replace("\n", "")
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Characteristic with no CCCD descriptor — simulates command chars or notify chars without CCC. */
    private fun commandChar(uuid: String): BluetoothGattCharacteristic {
        val char = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { char.uuid } returns UUID.fromString(uuid)
        every { char.getDescriptor(any()) } returns null
        return char
    }

    /** Notify characteristic that has a CCCD descriptor. */
    private fun notifyCharWithCccd(uuid: String): BluetoothGattCharacteristic {
        val char = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { char.uuid } returns UUID.fromString(uuid)
        every { char.getDescriptor(any()) } returns descriptor
        return char
    }

    private fun serviceWith(vararg chars: BluetoothGattCharacteristic): BluetoothGattService {
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.characteristics } returns chars.toList()
        return service
    }
}
