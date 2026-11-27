package app.goultra.transfer.ui

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

internal data class BeatMix(val beats: List<Long>, val musicGain: Float, val clickGain: Float = 1f)

internal fun beatClick(rate: Int): FloatArray = FloatArray(rate / 40) { frame ->
    val seconds = frame.toDouble() / rate
    (0.18 * sin(2 * PI * 1_400 * seconds) * exp(-seconds * 180)).toFloat()
}

internal fun mixBeatBuffer(
    buffer: ByteBuffer,
    firstFrame: Long,
    rate: Int,
    channels: Int,
    floatPcm: Boolean,
    click: FloatArray,
    mix: BeatMix,
) {
    val bytes = if (floatPcm) 4 else 2
    val frames = buffer.remaining() / (channels * bytes)
    fun apply(frame: Int, gain: Float, addition: Float) {
        for (channel in 0 until channels) {
            val byte = buffer.position() + (frame * channels + channel) * bytes
            if (floatPcm) buffer.putFloat(byte, (buffer.getFloat(byte) * gain + addition).coerceIn(-1f, 1f))
            else {
                val value = (buffer.getShort(byte) * gain + addition * Short.MAX_VALUE).roundToInt()
                buffer.putShort(byte, value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
    }
    if (mix.musicGain != 1f) for (frame in 0 until frames) apply(frame, mix.musicGain, 0f)
    if (click.isEmpty() || mix.beats.isEmpty() || mix.clickGain == 0f) return
    val earliestMs = (firstFrame - click.size) * 1000 / rate - 1
    val found = mix.beats.binarySearch(earliestMs)
    for (i in (if (found >= 0) found else -found - 1) until mix.beats.size) {
        val start = (mix.beats[i] * rate / 1000.0).roundToLong() - firstFrame
        if (start >= frames) break
        if (start + click.size <= 0) continue
        for (frame in maxOf(0, start.toInt()) until minOf(frames, start.toInt() + click.size)) {
            apply(frame, 1f, click[frame - start.toInt()] * mix.clickGain)
        }
    }
}
