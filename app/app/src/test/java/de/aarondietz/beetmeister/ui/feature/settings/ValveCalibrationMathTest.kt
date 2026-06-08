package de.aarondietz.beetmeister.ui.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValveCalibrationMathTest {
    @Test
    fun validatesPulseRanges() {
        assertTrue(isValidValvePulseRange(500, 2500))
        assertTrue(isValidValvePulseRange(900, 2100))
        assertFalse(isValidValvePulseRange(500, 500))
        assertFalse(isValidValvePulseRange(400, 2500))
        assertFalse(isValidValvePulseRange(500, 2600))
    }

    @Test
    fun convertsPulseAndPercentConsistently() {
        assertEquals(0f, valvePulseToPercent(500, 500, 2500))
        assertEquals(50f, valvePulseToPercent(1500, 500, 2500))
        assertEquals(100f, valvePulseToPercent(2500, 500, 2500))

        assertEquals(500, valvePercentToPulse(0f, 500, 2500))
        assertEquals(1500, valvePercentToPulse(50f, 500, 2500))
        assertEquals(2500, valvePercentToPulse(100f, 500, 2500))
    }

    @Test
    fun clampsPreviewPulseToConfiguredRange() {
        assertEquals(900, clampValvePulse(700, 900, 2100))
        assertEquals(1700, clampValvePulse(1700, 900, 2100))
        assertEquals(2100, clampValvePulse(2300, 900, 2100))
    }
}
