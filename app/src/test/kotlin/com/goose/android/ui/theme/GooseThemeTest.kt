package com.goose.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GooseThemeTest {
    // ---- recoveryColor thresholds ----

    @Test
    fun recoveryColor_high_returnsGreen() {
        val colors = GooseMetricColors()
        assertEquals(GooseRecoveryGreen, colors.recoveryColor(67))
        assertEquals(GooseRecoveryGreen, colors.recoveryColor(100))
    }

    @Test
    fun recoveryColor_mid_returnsYellow() {
        val colors = GooseMetricColors()
        assertEquals(GooseRecoveryYellow, colors.recoveryColor(34))
        assertEquals(GooseRecoveryYellow, colors.recoveryColor(66))
    }

    @Test
    fun recoveryColor_low_returnsRed() {
        val colors = GooseMetricColors()
        assertEquals(GooseRecoveryRed, colors.recoveryColor(0))
        assertEquals(GooseRecoveryRed, colors.recoveryColor(33))
    }

    // ---- Color design invariants ----

    @Test
    fun recoveryColors_areAllDistinct() {
        val colors = GooseMetricColors()
        assertTrue(colors.recoveryHigh != colors.recoveryMid)
        assertTrue(colors.recoveryMid != colors.recoveryLow)
        assertTrue(colors.recoveryHigh != colors.recoveryLow)
    }

    @Test
    fun recoveryLow_equalsCardioColor_byDesign() {
        val colors = GooseMetricColors()
        assertEquals(colors.recoveryLow, colors.cardio)
    }
}
