package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LoudnessAggregatorTest {

    @Test
    fun `empty list returns InsufficientAudio`() {
        val result = albumIntegratedLufs(emptyList())
        assertTrue(result is Either.Left, "expected Left; got $result")
        assertEquals(AnalysisError.InsufficientAudio, result.value)
    }

    @Test
    fun `single-track input passes through unchanged`() {
        val result = albumIntegratedLufs(listOf(-23.0))
        assertTrue(result is Either.Right, "expected Right; got $result")
        assertEquals(-23.0, result.value, 1e-9)
    }

    @Test
    fun `three identical tracks return same LUFS`() {
        val result = albumIntegratedLufs(listOf(-18.0, -18.0, -18.0))
        assertTrue(result is Either.Right, "expected Right; got $result")
        assertEquals(-18.0, result.value, 1e-9)
    }

    @Test
    fun `BS_1770-4 reference vector -23, -18, -28 yields ~ -21_26 LUFS`() {
        // Hand-computed in the plan's reference-math section.
        val result = albumIntegratedLufs(listOf(-23.0, -18.0, -28.0))
        assertTrue(result is Either.Right, "expected Right; got $result")
        val expected = -21.26
        assertTrue(
            abs(result.value - expected) < 0.01,
            "expected ~$expected, got ${result.value}",
        )
    }

    @Test
    fun `energy-weighted mean is biased toward louder tracks`() {
        // One loud track (-12) and one quiet track (-30) should land closer to
        // the loud value than the arithmetic mean (-21) would predict.
        val result = albumIntegratedLufs(listOf(-12.0, -30.0))
        assertTrue(result is Either.Right, "expected Right; got $result")
        val arithmeticMean = -21.0
        assertTrue(
            result.value.compareTo(arithmeticMean) > 0,
            "energy-weighted result ${result.value} should be > arithmetic mean $arithmeticMean " +
                "(closer to the louder track at -12 LUFS)",
        )
    }

    @Test
    fun `all silent tracks return NoGatedBlocks not negative infinity`() {
        when (val result = albumIntegratedLufs(listOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY))) {
            is Either.Left -> assertEquals(AnalysisError.NoGatedBlocks, result.value)
            is Either.Right -> fail("expected Left NoGatedBlocks, got Right(${result.value})")
        }
    }

    @Test
    fun `NaN input returns NoGatedBlocks not Right NaN`() {
        when (val result = albumIntegratedLufs(listOf(-18.0, Double.NaN))) {
            is Either.Left -> assertEquals(AnalysisError.NoGatedBlocks, result.value)
            is Either.Right -> fail("expected Left NoGatedBlocks, got Right(${result.value})")
        }
    }
}
