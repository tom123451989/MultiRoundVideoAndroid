package com.example.multiroundvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingMathTest {
    @Test
    fun repetitionMultiplierMatchesNotebookFormatting() {
        assertEquals("1024", formatRepetitionMultiplier(2, 10))
        assertEquals("1.024000 × 10^13", formatRepetitionMultiplier(20, 10))
    }

    @Test
    fun zeroRoundsCopiesOneOriginalSequence() {
        assertEquals("1", formatRepetitionMultiplier(25, 0))
        assertEquals("1.000e+00", formatApproximateTotalCopies(0, 0))
    }

    @Test
    fun zeroJoinCountWithZeroRoundsMatchesPythonPower() {
        assertEquals("1", formatRepetitionMultiplier(0, 0))
        assertEquals("0", formatRepetitionMultiplier(0, 2))
    }

    @Test
    fun totalCopiesUsesThreeDigitScientificNotation() {
        assertEquals("1.024e+13", formatApproximateTotalCopies(20, 10))
    }

    @Test
    fun largeMultiplierUsesScientificNotation() {
        val formatted = formatRepetitionMultiplier(20, 5000)
        assertTrue(formatted.contains("× 10^"))
    }

    @Test
    fun highTempoUsesFastLinearInterpolationFilter() {
        val filter = buildAtempoFilter(1000.0, 150.0)
        assertTrue(filter.contains("asetrate=3840000"))
        assertTrue(filter.contains("aresample=44100:async=1:linear_interp=1"))
        assertTrue(filter.contains("volume=1.5"))
    }

    @Test
    fun lowTempoUsesStandardAtempoFilter() {
        val filter = buildAtempoFilter(2.0, 100.0)
        assertTrue(filter.contains("atempo=2.0"))
        assertTrue(filter.contains("volume=1.0"))
    }

    @Test
    fun calculateEtaSecondsCalculatesCorrectRemainingTime() {
        assertEquals(10L, calculateEtaSeconds(10000L, 0.5f))
        assertEquals(30L, calculateEtaSeconds(10000L, 0.25f))
        assertNull(calculateEtaSeconds(500L, 0.005f))
    }

    @Test
    fun formatEtaFormatsSecondsCorrectly() {
        assertEquals("Calculating ETA…", formatEta(null))
        assertEquals("Finishing up…", formatEta(0L))
        assertEquals("45s", formatEta(45L))
        assertEquals("02:05", formatEta(125L))
        assertEquals("1:02:05", formatEta(3725L))
    }

    @Test
    fun calculateIntelliSpeedCalculatesCorrectSpeedForTargetDuration() {
        assertEquals(100.0, calculateIntelliSpeed(30.0, 100, 30.0), 0.001)
        assertEquals(150.0, calculateIntelliSpeed(45.0, 100, 30.0), 0.001)
    }

    @Test
    fun calculateTargetBitrateCalculatesBitrateMatchingFileSize() {
        assertEquals("4194k", calculateTargetBitrate(15728640L, 30.0))
    }
}
