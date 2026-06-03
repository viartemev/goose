package com.goose.android.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val NOTIFICATION_CHANNEL_CAPACITY = 64

/**
 * Back-pressure queue for raw BLE notification bytes → parsed [WhoopFrame]s.
 *
 * [push] is called from the GATT binder thread for every incoming notification.
 * A consumer coroutine reads from the bounded channel, parses with [WhoopFrameParser],
 * and emits complete frames to [frames].
 *
 * When the channel is full, the oldest chunk is silently dropped (DROP_OLDEST)
 * and [droppedNotificationCount] is incremented.
 *
 * Analogous to WhoopDataSignalPipeline in GooseSwift.
 */
class NotificationPipeline(
    private val scope: CoroutineScope,
    private val channelCapacity: Int = NOTIFICATION_CHANNEL_CAPACITY,
) {
    private val lock = Any()
    private val parserLock = Any()

    private val parser = WhoopFrameParser()
    private var generation = 0
    private var resetting = false
    private var channel = newChannel()
    private var consumerJob: Job = startConsumer(channel, generation)

    private val _frames =
        MutableSharedFlow<WhoopFrame>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val frames: SharedFlow<WhoopFrame> = _frames.asSharedFlow()

    private val depthAtomic = AtomicInteger(0)
    private val droppedAtomic = AtomicLong(0)

    /** Approximate number of notification chunks waiting to be parsed. */
    val queueDepth: Int get() = depthAtomic.get()

    /** Notification chunks dropped because the channel was full (best-effort count). */
    val droppedNotificationCount: Long get() = droppedAtomic.get()

    /** Bytes discarded by the frame parser (garbage between valid frame starts). */
    val parserDroppedByteCount: Int get() = parser.droppedFrameCount

    /** Called from the GATT binder thread on every BLE notification. */
    fun push(chunk: ByteArray) {
        var depthIncremented = false
        val target =
            synchronized(lock) {
                if (resetting) {
                    droppedAtomic.incrementAndGet()
                    return
                }
                ensureConsumerLocked()
                if (depthAtomic.get() >= channelCapacity) {
                    droppedAtomic.incrementAndGet()
                } else {
                    depthAtomic.incrementAndGet()
                    depthIncremented = true
                }
                channel
            }

        val result = target.trySend(chunk)
        if (!result.isSuccess) {
            if (depthIncremented) decrementDepth()
            droppedAtomic.incrementAndGet()
        }
    }

    fun reset() {
        synchronized(lock) {
            resetting = true
            generation++
            channel.cancel()
            consumerJob.cancel()
            droppedAtomic.set(0)
            depthAtomic.set(0)
        }
        synchronized(parserLock) {
            parser.reset()
        }
        synchronized(lock) {
            channel = newChannel()
            consumerJob = startConsumer(channel, generation)
            resetting = false
        }
    }

    /** Closes the channel so the consumer coroutine exits gracefully. */
    fun close() {
        synchronized(lock) {
            generation++
            channel.cancel()
            consumerJob.cancel()
            depthAtomic.set(0)
        }
    }

    private fun newChannel(): Channel<ByteArray> =
        Channel(
            capacity = channelCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private fun ensureConsumerLocked() {
        if (!channel.isClosedForSend && consumerJob.isActive) return
        generation++
        channel = newChannel()
        consumerJob = startConsumer(channel, generation)
        depthAtomic.set(0)
    }

    private fun startConsumer(
        source: Channel<ByteArray>,
        consumerGeneration: Int,
    ): Job =
        scope.launch {
            for (chunk in source) {
                decrementDepth()
                if (!isCurrentGeneration(consumerGeneration)) continue

                val parsed =
                    synchronized(parserLock) {
                        parser.push(chunk)
                    }
                if (!isCurrentGeneration(consumerGeneration)) continue

                parsed.forEach { frame -> _frames.emit(frame) }
            }
        }

    private fun isCurrentGeneration(consumerGeneration: Int): Boolean = synchronized(lock) { generation == consumerGeneration }

    private fun decrementDepth() {
        while (true) {
            val current = depthAtomic.get()
            val next = (current - 1).coerceAtLeast(0)
            if (depthAtomic.compareAndSet(current, next)) return
        }
    }
}
