package com.goose.android.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPipelineTest {
    // ---- helpers ----

    private fun String.hexToBytes(): ByteArray {
        val cleaned = replace(" ", "").replace("\n", "")
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Valid GET_HELLO command frame. */
    private val helloHex = "aa0108000001e67123019101363e5c8d"

    /** Valid EVENT (temperature) frame. */
    private val eventHex = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e"

    /** Valid HISTORICAL_DATA frame. */
    private val historicalHex = "aa0118000001e2b12f120104030201443322116655aa4dbbccddeeff475fc4d7"

    // backgroundScope avoids UncompletedCoroutinesError for the consumer's infinite for-loop.
    private fun makeScope() = TestScope(StandardTestDispatcher())

    // ---- single complete frame ----

    @Test
    fun push_singleCompleteFrame_emitsOneFrame() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle() // start consumer + collector before any push

        pipeline.push(helloHex.hexToBytes())
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertTrue(collected[0] is WhoopFrame.Command)

        job.cancelAndJoin()
    }

    // ---- frame split across two pushes ----

    @Test
    fun push_splitFrame_reassemblesAndEmitsAfterSecondPush() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        val bytes = helloHex.hexToBytes()
        val mid = bytes.size / 2

        pipeline.push(bytes.sliceArray(0 until mid))
        advanceUntilIdle()
        assertEquals(0, collected.size)

        pipeline.push(bytes.sliceArray(mid until bytes.size))
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertTrue(collected[0] is WhoopFrame.Command)

        job.cancelAndJoin()
    }

    // ---- two frames in one push ----

    @Test
    fun push_twoFramesConcatenated_emitsBothFrames() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push((helloHex + eventHex).hexToBytes())
        advanceUntilIdle()

        assertEquals(2, collected.size)
        assertTrue(collected[0] is WhoopFrame.Command)
        assertTrue(collected[1] is WhoopFrame.Event)

        job.cancelAndJoin()
    }

    // ---- three distinct frame types ----

    @Test
    fun push_allThreeFrameTypes_emittedInOrder() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push(helloHex.hexToBytes())
        pipeline.push(eventHex.hexToBytes())
        pipeline.push(historicalHex.hexToBytes())
        advanceUntilIdle()

        assertEquals(3, collected.size)
        assertTrue(collected[0] is WhoopFrame.Command)
        assertTrue(collected[1] is WhoopFrame.Event)
        assertTrue(collected[2] is WhoopFrame.HistoricalData)

        job.cancelAndJoin()
    }

    // ---- empty push emits nothing ----

    @Test
    fun push_emptyBytes_emitsNothing() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push(byteArrayOf())
        advanceUntilIdle()

        assertTrue(collected.isEmpty())

        job.cancelAndJoin()
    }

    // ---- garbage bytes increment parserDroppedByteCount ----

    @Test
    fun push_garbageBytes_incrementsParserDroppedByteCount() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val job = launch { pipeline.frames.collect {} }
        advanceUntilIdle()

        pipeline.push(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        advanceUntilIdle()

        assertTrue(pipeline.parserDroppedByteCount > 0)

        job.cancelAndJoin()
    }

    @Test
    fun push_garbagePrefixThenValidFrame_dropsGarbageAndEmitsFrame() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        val garbage = byteArrayOf(0xde.toByte(), 0xad.toByte())
        pipeline.push(garbage + helloHex.hexToBytes())
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertTrue(pipeline.parserDroppedByteCount >= 2)

        job.cancelAndJoin()
    }

    // ---- queue depth ----

    @Test
    fun queueDepth_zeroInitially() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        assertEquals(0, pipeline.queueDepth)
    }

    @Test
    fun queueDepth_incrementsOnPush_decrementsAfterConsume() {
        val scope = makeScope()
        val pipeline = NotificationPipeline(scope.backgroundScope)

        // Consumer not yet started (StandardTestDispatcher is lazy)
        pipeline.push(helloHex.hexToBytes())
        assertEquals(1, pipeline.queueDepth)

        scope.advanceUntilIdle()
        assertEquals(0, pipeline.queueDepth)

        scope.cancel()
    }

    // ---- back-pressure: droppedNotificationCount ----

    @Test
    fun droppedNotificationCount_zeroInitially() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        assertEquals(0L, pipeline.droppedNotificationCount)
    }

    @Test
    fun droppedNotificationCount_incrementsWhenChannelFull() {
        // Capacity=2: fill channel before consumer starts, then push a third → drop
        val scope = makeScope()
        val pipeline = NotificationPipeline(scope.backgroundScope, channelCapacity = 2)

        pipeline.push(helloHex.hexToBytes()) // depth=1
        pipeline.push(helloHex.hexToBytes()) // depth=2 (full)
        assertEquals(0L, pipeline.droppedNotificationCount)

        pipeline.push(helloHex.hexToBytes()) // channel full → drop oldest
        assertEquals(1L, pipeline.droppedNotificationCount)

        scope.advanceUntilIdle()
        scope.cancel()
    }

    @Test
    fun droppedNotificationCount_accumulatesMultipleDrops() {
        val scope = makeScope()
        val pipeline = NotificationPipeline(scope.backgroundScope, channelCapacity = 1)

        pipeline.push(helloHex.hexToBytes()) // depth=1 (full)
        pipeline.push(helloHex.hexToBytes()) // drop
        pipeline.push(helloHex.hexToBytes()) // drop

        assertEquals(2L, pipeline.droppedNotificationCount)

        scope.advanceUntilIdle()
        scope.cancel()
    }

    // ---- reset ----

    @Test
    fun reset_clearsDroppedNotificationCount() {
        val scope = makeScope()
        val pipeline = NotificationPipeline(scope.backgroundScope, channelCapacity = 1)

        pipeline.push(helloHex.hexToBytes()) // full
        pipeline.push(helloHex.hexToBytes()) // drop
        assertEquals(1L, pipeline.droppedNotificationCount)

        pipeline.reset()
        assertEquals(0L, pipeline.droppedNotificationCount)

        scope.cancel()
    }

    @Test
    fun reset_clearsPartialParserBuffer() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        val bytes = helloHex.hexToBytes()
        // Push only half — starts accumulating in parser buffer
        pipeline.push(bytes.sliceArray(0 until bytes.size / 2))
        advanceUntilIdle()
        assertEquals(0, collected.size)

        pipeline.reset()

        // After reset, full frame must produce exactly 1 frame (no stale bytes)
        pipeline.push(bytes)
        advanceUntilIdle()
        assertEquals(1, collected.size)

        job.cancelAndJoin()
    }

    // ---- byte-by-byte accumulation ----

    @Test
    fun push_byteByByte_reassemblesCompleteFrame() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        val bytes = helloHex.hexToBytes()
        for (byte in bytes) {
            pipeline.push(byteArrayOf(byte))
        }
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertTrue(collected[0] is WhoopFrame.Command)

        job.cancelAndJoin()
    }

    // ---- frame field values ----

    @Test
    fun emittedCommandFrame_hasCorrectFieldValues() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push(helloHex.hexToBytes())
        advanceUntilIdle()

        val frame = collected[0] as WhoopFrame.Command
        assertEquals(WhoopFrameConstants.PACKET_TYPE_COMMAND, frame.packetType)
        assertEquals(1.toByte(), frame.sequence)
        assertEquals(WhoopFrameConstants.COMMAND_GET_HELLO, frame.command)
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)
        assertTrue(frame.warnings.isEmpty())

        job.cancelAndJoin()
    }

    @Test
    fun emittedEventFrame_hasCorrectFieldValues() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push(eventHex.hexToBytes())
        advanceUntilIdle()

        val frame = collected[0] as WhoopFrame.Event
        assertEquals(WhoopFrameConstants.PACKET_TYPE_EVENT, frame.packetType)
        assertEquals(2.toByte(), frame.sequence)
        assertEquals(17.toUShort(), frame.eventId)
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)

        job.cancelAndJoin()
    }

    @Test
    fun emittedHistoricalFrame_hasCorrectFieldValues() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val collected = mutableListOf<WhoopFrame>()
        val job = launch { pipeline.frames.collect { collected.add(it) } }
        advanceUntilIdle()

        pipeline.push(historicalHex.hexToBytes())
        advanceUntilIdle()

        val frame = collected[0] as WhoopFrame.HistoricalData
        assertEquals(WhoopFrameConstants.PACKET_TYPE_HISTORICAL_DATA, frame.packetType)
        assertEquals(18.toByte(), frame.sequence)
        assertEquals(18.toByte(), frame.packetK)
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)

        job.cancelAndJoin()
    }

    // ---- multiple subscribers ----

    @Test
    fun frames_multipleCollectors_allReceiveSameFrames() = runTest {
        val pipeline = NotificationPipeline(backgroundScope)
        val results1 = mutableListOf<WhoopFrame>()
        val results2 = mutableListOf<WhoopFrame>()
        val job1 = launch { pipeline.frames.collect { results1.add(it) } }
        val job2 = launch { pipeline.frames.collect { results2.add(it) } }
        advanceUntilIdle()

        pipeline.push(helloHex.hexToBytes())
        advanceUntilIdle()

        assertEquals(1, results1.size)
        assertEquals(1, results2.size)

        job1.cancelAndJoin()
        job2.cancelAndJoin()
    }
}
