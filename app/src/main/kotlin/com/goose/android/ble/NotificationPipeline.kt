package com.goose.android.ble

import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope,
    private val channelCapacity: Int = NOTIFICATION_CHANNEL_CAPACITY,
) {
    private val channel =
        Channel<ByteArray>(
            capacity = channelCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val parser = WhoopFrameParser()

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

    init {
        scope.launch {
            for (chunk in channel) {
                depthAtomic.decrementAndGet()
                val parsed = parser.push(chunk)
                parsed.forEach { frame -> _frames.emit(frame) }
            }
        }
    }

    /** Called from the GATT binder thread on every BLE notification. */
    fun push(chunk: ByteArray) {
        // Best-effort drop accounting: if depth is already at capacity, the channel will
        // silently drop the oldest item to make room (DROP_OLDEST), so count it here.
        if (depthAtomic.get() >= channelCapacity) {
            droppedAtomic.incrementAndGet()
        } else {
            depthAtomic.incrementAndGet()
        }
        channel.trySend(chunk)
    }

    fun reset() {
        parser.reset()
        droppedAtomic.set(0)
        depthAtomic.set(0)
    }

    /** Closes the channel so the consumer coroutine exits gracefully. */
    fun close() {
        channel.close()
    }
}
