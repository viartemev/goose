package com.goose.android.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Processes real-time BLE vitals into rate-limited, windowed estimates.
 *
 * Ported from GooseBLEClient+VitalsAndLogging.swift.
 * All public methods are thread-safe (called from both the GATT binder thread and coroutines).
 */
class VitalsProcessor {
    private val _liveHeartRate = MutableStateFlow<Int?>(null)
    val liveHeartRate: StateFlow<Int?> = _liveHeartRate.asStateFlow()

    private val _liveHRV = MutableStateFlow<Double?>(null)
    val liveHRV: StateFlow<Double?> = _liveHRV.asStateFlow()

    private val _restingHeartRate = MutableStateFlow<Double?>(null)
    val restingHeartRate: StateFlow<Double?> = _restingHeartRate.asStateFlow()

    // HR rate-limiting
    private var lastHRPublishMs = 0L

    // Resting HR window (low-quartile mean of recent HR samples)
    private val restingHRWindowBpm = ArrayDeque<Int>()
    private var lastRestingHRPublishMs = 0L

    // HRV: accumulate RR intervals into chunks, then average RMSSD across chunks
    private val rrIntervalChunkMs = mutableListOf<Double>()
    private var rrIntervalChunkStartMs: Long? = null
    private val hrvRmssdSamples = ArrayDeque<Pair<Double, Int>>() // (rmssd, rrCount)
    private var lastHRVPublishMs = 0L

    @Synchronized
    fun processHeartRate(bpm: Int, nowMs: Long = System.currentTimeMillis()) {
        if (bpm !in HR_MIN..HR_MAX) return

        val shouldPublish = _liveHeartRate.value == null ||
            nowMs - lastHRPublishMs >= HR_PUBLISH_INTERVAL_MS
        if (shouldPublish) {
            lastHRPublishMs = nowMs
            _liveHeartRate.value = bpm
        }

        processRestingHREstimate(bpm, nowMs)
    }

    @Synchronized
    fun processRRIntervals(intervalsMs: List<Double>, nowMs: Long = System.currentTimeMillis()) {
        val valid = intervalsMs.filter { it in RR_VALID_MIN..RR_VALID_MAX }
        if (valid.isEmpty()) return

        if (rrIntervalChunkStartMs == null) rrIntervalChunkStartMs = nowMs
        rrIntervalChunkMs.addAll(valid)

        val chunkAge = nowMs - rrIntervalChunkStartMs!!
        val shouldFinalize = rrIntervalChunkMs.size >= HRV_CHUNK_SIZE ||
            (rrIntervalChunkMs.size >= HRV_MIN_RR_PER_CHUNK && chunkAge >= HRV_CHUNK_MAX_AGE_MS)
        if (!shouldFinalize) return

        val chunkRmssd = rmssdMs(rrIntervalChunkMs) ?: run {
            rrIntervalChunkMs.clear()
            rrIntervalChunkStartMs = null
            return
        }
        val chunkRRCount = rrIntervalChunkMs.size
        rrIntervalChunkMs.clear()
        rrIntervalChunkStartMs = null

        hrvRmssdSamples.add(chunkRmssd to chunkRRCount)
        while (hrvRmssdSamples.size > HRV_RMSSD_WINDOW_SIZE) hrvRmssdSamples.removeFirst()

        val totalCount = hrvRmssdSamples.sumOf { it.second }
        if (totalCount == 0) return
        val weightedRmssd = hrvRmssdSamples.sumOf { it.first * it.second } / totalCount

        val shouldPublish = _liveHRV.value == null ||
            nowMs - lastHRVPublishMs >= HRV_PUBLISH_INTERVAL_MS
        if (shouldPublish) {
            lastHRVPublishMs = nowMs
            _liveHRV.value = weightedRmssd
        }
    }

    @Synchronized
    fun reset() {
        _liveHeartRate.value = null
        _liveHRV.value = null
        _restingHeartRate.value = null
        lastHRPublishMs = 0L
        restingHRWindowBpm.clear()
        lastRestingHRPublishMs = 0L
        rrIntervalChunkMs.clear()
        rrIntervalChunkStartMs = null
        hrvRmssdSamples.clear()
        lastHRVPublishMs = 0L
    }

    private fun processRestingHREstimate(bpm: Int, nowMs: Long) {
        restingHRWindowBpm.add(bpm)
        while (restingHRWindowBpm.size > RESTING_HR_WINDOW_SIZE) restingHRWindowBpm.removeFirst()
        if (restingHRWindowBpm.size < RESTING_HR_MIN_SAMPLES) return

        val estimate = lowQuartileMeanBpm(restingHRWindowBpm.toList())
        if (!estimate.isFinite() || estimate.roundToInt() !in HR_MIN..HR_MAX) return

        val shouldPublish = _restingHeartRate.value == null ||
            nowMs - lastRestingHRPublishMs >= RESTING_HR_PUBLISH_INTERVAL_MS
        if (!shouldPublish) return

        lastRestingHRPublishMs = nowMs
        _restingHeartRate.value = estimate
    }

    companion object {
        const val HR_MIN = 20
        const val HR_MAX = 240
        const val HR_PUBLISH_INTERVAL_MS = 1_000L
        const val RESTING_HR_WINDOW_SIZE = 300
        const val RESTING_HR_MIN_SAMPLES = 12
        const val RESTING_HR_PUBLISH_INTERVAL_MS = 60_000L
        const val RR_VALID_MIN = 300.0
        const val RR_VALID_MAX = 2000.0
        const val HRV_CHUNK_SIZE = 30
        const val HRV_MIN_RR_PER_CHUNK = 10
        const val HRV_CHUNK_MAX_AGE_MS = 60_000L
        const val HRV_RMSSD_WINDOW_SIZE = 12
        const val HRV_PUBLISH_INTERVAL_MS = 60_000L

        /**
         * Parses a standard BLE Heart Rate Measurement (characteristic 0x2A37).
         * Returns (bpm, rrIntervalsMs) or null if the payload is malformed.
         *
         * Ported from GooseBLEClient+Parsing.swift parseStandardHeartRateMeasurement.
         */
        fun parseStandardHRMeasurement(value: ByteArray): Pair<Int, List<Double>>? {
            if (value.size < 2) return null
            val flags = value[0].toInt() and 0xff
            var offset = 1

            val bpm: Int
            if (flags and 0x01 == 0) {
                bpm = value[offset].toInt() and 0xff
                offset++
            } else {
                if (value.size < offset + 2) return null
                bpm = (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)
                offset += 2
            }

            // Skip energy expended field (2 bytes) if present (bit 3)
            if (flags and 0x08 != 0) {
                if (value.size < offset + 2) return bpm to emptyList()
                offset += 2
            }

            // Parse RR intervals (1/1024 s units → ms) if present (bit 4)
            val rrMs = mutableListOf<Double>()
            if (flags and 0x10 != 0) {
                while (value.size >= offset + 2) {
                    val raw = (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)
                    rrMs.add(raw * 1000.0 / 1024.0)
                    offset += 2
                }
            }
            return bpm to rrMs
        }

        /** RMSSD of successive differences (ms). Returns null if fewer than 2 intervals. */
        fun rmssdMs(intervalsMs: List<Double>): Double? {
            if (intervalsMs.size < 2) return null
            var squaredDiffSum = 0.0
            var count = 0
            for (i in 1 until intervalsMs.size) {
                val diff = intervalsMs[i] - intervalsMs[i - 1]
                squaredDiffSum += diff * diff
                count++
            }
            return if (count > 0) sqrt(squaredDiffSum / count) else null
        }

        /** Mean of the lowest quartile of samples — proxy for resting HR. */
        fun lowQuartileMeanBpm(samples: List<Int>): Double {
            val sorted = samples.sorted()
            val count = max(1, sorted.size / 4)
            return sorted.take(count).sum().toDouble() / count
        }
    }
}
