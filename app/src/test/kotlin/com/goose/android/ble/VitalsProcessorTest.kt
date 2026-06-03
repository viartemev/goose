package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class VitalsProcessorTest {
    private lateinit var processor: VitalsProcessor

    @Before
    fun setUp() {
        processor = VitalsProcessor()
    }

    // ---- parseStandardHRMeasurement ----

    @Test
    fun parse_8bitHR_noRR() {
        // flags=0x00 (8-bit HR, no energy, no RR), bpm=72
        val bytes = byteArrayOf(0x00, 72)
        val result = VitalsProcessor.parseStandardHRMeasurement(bytes)
        assertNotNull(result)
        assertEquals(72, result!!.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun parse_16bitHR_noRR() {
        // flags=0x01 (16-bit HR), bpm=180 = 0x00B4 LE
        val bytes = byteArrayOf(0x01, 0xB4.toByte(), 0x00)
        val result = VitalsProcessor.parseStandardHRMeasurement(bytes)
        assertNotNull(result)
        assertEquals(180, result!!.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun parse_8bitHR_withRR() {
        // flags=0x10 (8-bit HR, RR present), bpm=60
        // RR = 1024 ticks → 1000 ms
        val bytes = byteArrayOf(0x10, 60, 0x00, 0x04) // 1024 = 0x0400 LE
        val result = VitalsProcessor.parseStandardHRMeasurement(bytes)
        assertNotNull(result)
        assertEquals(60, result!!.first)
        assertEquals(1, result.second.size)
        assertEquals(1000.0, result.second[0], 0.1)
    }

    @Test
    fun parse_8bitHR_withEnergyExpended_andRR() {
        // flags=0x18 (8-bit HR, energy expended + RR), bpm=75
        // energy expended = 0x0064 (100 kJ, 2 bytes)
        // RR = 512 ticks → 500 ms
        val bytes = byteArrayOf(0x18, 75, 0x64, 0x00, 0x00, 0x02) // 512 = 0x0200 LE
        val result = VitalsProcessor.parseStandardHRMeasurement(bytes)
        assertNotNull(result)
        assertEquals(75, result!!.first)
        assertEquals(1, result.second.size)
        assertEquals(500.0, result.second[0], 0.5)
    }

    @Test
    fun parse_multipleRRIntervals() {
        // flags=0x10 (8-bit HR, RR), bpm=65, two RR intervals
        val rr1 = 0x0400 // 1024 ticks = 1000 ms
        val rr2 = 0x0800 // 2048 ticks = 2000 ms
        val bytes = byteArrayOf(
            0x10, 65,
            (rr1 and 0xff).toByte(), (rr1 shr 8).toByte(),
            (rr2 and 0xff).toByte(), (rr2 shr 8).toByte(),
        )
        val result = VitalsProcessor.parseStandardHRMeasurement(bytes)
        assertNotNull(result)
        assertEquals(2, result!!.second.size)
        assertEquals(1000.0, result.second[0], 0.1)
        assertEquals(2000.0, result.second[1], 0.2)
    }

    @Test
    fun parse_tooShort_returnsNull() {
        assertNull(VitalsProcessor.parseStandardHRMeasurement(byteArrayOf(0x00)))
        assertNull(VitalsProcessor.parseStandardHRMeasurement(byteArrayOf()))
    }

    // ---- processHeartRate — initial state ----

    @Test
    fun initialState_allNulls() {
        assertNull(processor.liveHeartRate.value)
        assertNull(processor.liveHRV.value)
        assertNull(processor.restingHeartRate.value)
    }

    // ---- processHeartRate — range validation ----

    @Test
    fun hrBelowMin_rejected() {
        processor.processHeartRate(19)
        assertNull(processor.liveHeartRate.value)
    }

    @Test
    fun hrAboveMax_rejected() {
        processor.processHeartRate(241)
        assertNull(processor.liveHeartRate.value)
    }

    @Test
    fun hrAtBoundaries_accepted() {
        processor.processHeartRate(20)
        assertEquals(20, processor.liveHeartRate.value)

        processor.reset()
        processor.processHeartRate(240)
        assertEquals(240, processor.liveHeartRate.value)
    }

    // ---- processHeartRate — rate limiting ----

    @Test
    fun firstHR_publishedImmediately() {
        processor.processHeartRate(70, nowMs = 0L)
        assertEquals(70, processor.liveHeartRate.value)
    }

    @Test
    fun secondHR_beforeInterval_notPublished() {
        processor.processHeartRate(70, nowMs = 0L)
        processor.processHeartRate(80, nowMs = 500L) // 500 ms < 1000 ms interval
        assertEquals(70, processor.liveHeartRate.value) // still old value
    }

    @Test
    fun secondHR_afterInterval_published() {
        processor.processHeartRate(70, nowMs = 0L)
        processor.processHeartRate(80, nowMs = 1_001L)
        assertEquals(80, processor.liveHeartRate.value)
    }

    // ---- restingHeartRate — window and computation ----

    @Test
    fun restingHR_belowMinSamples_notPublished() {
        repeat(VitalsProcessor.RESTING_HR_MIN_SAMPLES - 1) {
            processor.processHeartRate(60, nowMs = it.toLong() * 2_000L)
        }
        assertNull(processor.restingHeartRate.value)
    }

    @Test
    fun restingHR_atMinSamples_published() {
        // Feed 12 identical samples — low-quartile mean = same value
        repeat(VitalsProcessor.RESTING_HR_MIN_SAMPLES) {
            processor.processHeartRate(65, nowMs = it.toLong() * 2_000L)
        }
        assertNotNull(processor.restingHeartRate.value)
        assertEquals(65.0, processor.restingHeartRate.value!!, 0.01)
    }

    @Test
    fun restingHR_lowQuartileMean_correctValue() {
        // Feed samples 100, 100, 100, 100 then 12 samples of 60
        // Low quartile of 16 samples = lowest 4 → all 60s → mean = 60
        val samples = List(4) { 100 } + List(12) { 60 }
        var t = 0L
        for (bpm in samples) {
            processor.processHeartRate(bpm, nowMs = t)
            t += 2_000L
        }
        assertNotNull(processor.restingHeartRate.value)
        assertEquals(60.0, processor.restingHeartRate.value!!, 0.01)
    }

    @Test
    fun restingHR_rateLimit_secondPublishRequiresInterval() {
        // Fill up min samples
        repeat(VitalsProcessor.RESTING_HR_MIN_SAMPLES) { i ->
            processor.processHeartRate(65, nowMs = i.toLong() * 2_000L)
        }
        val firstEstimate = processor.restingHeartRate.value
        assertNotNull(firstEstimate)

        // 30 seconds later — should NOT publish yet (interval is 60s)
        val baseTime = VitalsProcessor.RESTING_HR_MIN_SAMPLES.toLong() * 2_000L
        processor.processHeartRate(65, nowMs = baseTime + 30_000L)
        assertEquals(firstEstimate, processor.restingHeartRate.value)

        // 60+ seconds later — should publish
        processor.processHeartRate(65, nowMs = baseTime + 61_000L)
        assertNotNull(processor.restingHeartRate.value)
    }

    // ---- rmssdMs ----

    @Test
    fun rmssd_knownIntervals() {
        // intervals: 800, 900, 800 → diffs: 100, -100 → RMSSD = sqrt((10000+10000)/2) = 100.0
        val result = VitalsProcessor.rmssdMs(listOf(800.0, 900.0, 800.0))
        assertNotNull(result)
        assertEquals(100.0, result!!, 0.001)
    }

    @Test
    fun rmssd_twoIdenticalIntervals_zero() {
        val result = VitalsProcessor.rmssdMs(listOf(800.0, 800.0))
        assertNotNull(result)
        assertEquals(0.0, result!!, 0.001)
    }

    @Test
    fun rmssd_singleInterval_null() {
        assertNull(VitalsProcessor.rmssdMs(listOf(800.0)))
    }

    @Test
    fun rmssd_emptyList_null() {
        assertNull(VitalsProcessor.rmssdMs(emptyList()))
    }

    // ---- lowQuartileMeanBpm ----

    @Test
    fun lowQuartile_uniformSamples() {
        assertEquals(50.0, VitalsProcessor.lowQuartileMeanBpm(List(100) { 50 }), 0.01)
    }

    @Test
    fun lowQuartile_mixedSamples_returnsLowQuarter() {
        // 4 high values (100) and 12 low values (60) → quartile of 16 = 4 lowest = all 60
        val samples = List(4) { 100 } + List(12) { 60 }
        assertEquals(60.0, VitalsProcessor.lowQuartileMeanBpm(samples), 0.01)
    }

    // ---- processRRIntervals — filtering ----

    @Test
    fun rrBelowMin_filtered() {
        // 299 ms is below 300 ms threshold — should not contribute to HRV chunk
        processor.processRRIntervals(listOf(299.0), nowMs = 0L)
        // After feeding insufficient valid RR, HRV should remain null
        assertNull(processor.liveHRV.value)
    }

    @Test
    fun rrAboveMax_filtered() {
        processor.processRRIntervals(listOf(2001.0), nowMs = 0L)
        assertNull(processor.liveHRV.value)
    }

    // ---- processRRIntervals — chunk accumulation and HRV ----

    @Test
    fun hrv_publishedAfterFullChunk() {
        // Feed exactly HRV_CHUNK_SIZE valid RR intervals at t=0
        val rrs = List(VitalsProcessor.HRV_CHUNK_SIZE) { 800.0 + it % 2 * 10.0 } // alternating 800/810
        processor.processRRIntervals(rrs, nowMs = 0L)
        assertNotNull(processor.liveHRV.value)
    }

    @Test
    fun hrv_belowChunkSize_belowMaxAge_notPublished() {
        val rrs = List(VitalsProcessor.HRV_MIN_RR_PER_CHUNK - 1) { 800.0 }
        processor.processRRIntervals(rrs, nowMs = 0L)
        assertNull(processor.liveHRV.value)
    }

    @Test
    fun hrv_aboveMinRR_atMaxAge_published() {
        // Feed HRV_MIN_RR_PER_CHUNK intervals, then trigger finalization by exceeding max age
        val rrs = List(VitalsProcessor.HRV_MIN_RR_PER_CHUNK) { 800.0 + it % 2 * 10.0 }
        processor.processRRIntervals(rrs, nowMs = 0L)
        assertNull(processor.liveHRV.value) // not enough time elapsed yet

        // Feed one more interval past the max age
        processor.processRRIntervals(listOf(800.0), nowMs = VitalsProcessor.HRV_CHUNK_MAX_AGE_MS + 1L)
        assertNotNull(processor.liveHRV.value)
    }

    // ---- reset ----

    @Test
    fun reset_clearsAllState() {
        processor.processHeartRate(72, nowMs = 0L)
        repeat(VitalsProcessor.RESTING_HR_MIN_SAMPLES) {
            processor.processHeartRate(65, nowMs = it.toLong() * 2_000L)
        }
        processor.processRRIntervals(List(VitalsProcessor.HRV_CHUNK_SIZE) { 800.0 }, nowMs = 0L)

        processor.reset()

        assertNull(processor.liveHeartRate.value)
        assertNull(processor.liveHRV.value)
        assertNull(processor.restingHeartRate.value)
    }

    @Test
    fun reset_thenFirstHR_publishedImmediately() {
        processor.processHeartRate(72, nowMs = 0L)
        processor.reset()
        processor.processHeartRate(80, nowMs = 0L)
        assertEquals(80, processor.liveHeartRate.value)
    }
}
