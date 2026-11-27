package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAccentsTest {
    private val rate = 8_000

    @Test
    fun `structure finds a harmonic change at constant loudness`() {
        val samples = FloatArray(24 * rate) { frame ->
            val hz = if (frame < 12 * rate) 220.0 else 440.0 * 1.5
            sin(2 * PI * hz * frame / rate).toFloat() * 0.3f
        }
        val result = AudioAccents.analyse(samples, rate, null, AudioAnalysisMode.AUTO)
        assertEquals(AudioAnalysisMode.STRUCTURE, result.mode)
        assertTrue(result.drops.toString(), result.drops.any { abs(it - 12_000) <= 500 })
    }

    @Test
    fun `speech marks a sustained phrase after a pause without a beat grid`() {
        val samples = FloatArray(8 * rate) { frame ->
            if (frame in 3 * rate until 4 * rate) 0f
            else sin(2 * PI * 180 * frame / rate).toFloat() * 0.3f
        }
        val result = AudioAccents.analyse(samples, rate, null, AudioAnalysisMode.SPEECH)
        assertEquals(listOf(4_000L), result.drops)
    }

    @Test
    fun `automatic analysis prefers rhythmic accent placement with a reliable beat grid`() {
        val samples = FloatArray(24 * rate) { frame ->
            val hz = if (frame < 12 * rate) 220.0 else 660.0
            sin(2 * PI * hz * frame / rate).toFloat() * 0.3f
        }
        val beats = (100L until 24_000L step 500).toList()
        val grid = BeatGrid.Grid(120.0, 100, beats, 0.8)
        val result = AudioAccents.analyse(samples, rate, grid, AudioAnalysisMode.AUTO)
        assertEquals(AudioAnalysisMode.RHYTHM, result.mode)
        assertTrue(result.drops.toString(), result.drops.isNotEmpty() && result.drops.all { it in beats })
    }

    @Test
    fun `dynamics pinpoints a sustained late rise despite a louder first section`() {
        val samples = FloatArray(126 * rate) { frame ->
            val level = when (frame / rate) {
                in 18..25 -> 0.72f
                in 105..113 -> 0.42f
                else -> 0.20f
            }
            sin(2 * PI * 220 * frame / rate).toFloat() * level
        }
        val result = AudioAccents.analyse(samples, rate, null, AudioAnalysisMode.DYNAMICS)
        assertTrue(result.drops.toString(), result.drops.any { abs(it - 18_000) <= 100 })
        assertTrue(result.drops.toString(), result.drops.any { abs(it - 105_000) <= 100 })
    }

    @Test
    fun `relative spectral features retain late structural changes at a lower recording level`() {
        val samples = FloatArray(105 * rate) { frame ->
            val hz = when (frame / rate) {
                in 18..41 -> 330.0
                in 82..104 -> 550.0
                else -> 220.0
            }
            sin(2 * PI * hz * frame / rate).toFloat() * 0.3f
        }
        val ref = AudioAccents.analyse(samples, rate, null, AudioAnalysisMode.STRUCTURE)
        val quiet = AudioAccents.analyse(FloatArray(samples.size) { samples[it] * 0.15f }, rate, null, AudioAnalysisMode.STRUCTURE)
        assertTrue(ref.drops.toString(), ref.drops.any { abs(it - 82_000) <= 500 })
        assertEquals(ref, quiet)
    }
}
