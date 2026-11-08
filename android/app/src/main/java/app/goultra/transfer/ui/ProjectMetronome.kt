package app.goultra.transfer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.goultra.transfer.camera.ProjectMusic
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** How long the click sounds, and how quickly it dies away — a tick, not a note. */
private const val CLICK_MS = 25
private const val CLICK_HZ = 1_400.0

/**
 * The click on the grid (P3): every beat of the track sounds where its red line stands, so a grid found by the analysis
 * or set by hand can be *heard*, not only seen. On every beat of the project's own clock, no accents — which beat of the
 * bar it is, is not something the analysis knows, and a guessed accent would be a lie.
 */
@Composable
internal fun ProjectMetronome(music: ProjectMusic?, timeMs: Long, playing: Boolean) {
    val beats = music?.beats.orEmpty()
    if (music == null || beats.isEmpty()) return
    val context = LocalContext.current
    val click = remember { Click(context) }
    DisposableEffect(click) { onDispose { click.release() } }
    // The clock comes in every 50 ms while the film plays: a beat is the tick a beat's moment fell between.
    var previous by remember(music.uri) { mutableLongStateOf(timeMs) }
    LaunchedEffect(timeMs, playing, AppPrefs.metronome) {
        val from = previous
        previous = timeMs
        if (!playing || !AppPrefs.metronome) return@LaunchedEffect
        // A jump (a seek, a piece put on) is not a beat to be heard; nor is the very first tick.
        if (timeMs <= from || timeMs - from > 400L) return@LaunchedEffect
        val offset = music.offsetMs
        if (beats.any { it - offset in (from + 1)..timeMs }) click.play()
    }
}

/** The tick itself: a short, quickly dying tone, made once and kept in the cache — a metronome must sound the same always. */
private class Click(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val id = pool.load(clickFile(context).absolutePath, 1)

    fun play() {
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}

/** A [CLICK_MS] tick of [CLICK_HZ], 16-bit mono, its head wound up as a WAV and written beside the app's cache. */
private fun clickFile(context: Context): File {
    val file = File(context.cacheDir, "metronome.wav")
    if (file.length() > 0L) return file
    val rate = 44_100
    val frames = rate * CLICK_MS / 1000
    val data = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val t = i.toDouble() / rate
        val sample = sin(2 * PI * CLICK_HZ * t) * exp(-t * 140.0) * 0.75
        val value = (sample * 32_767).roundToInt().coerceIn(-32_768, 32_767)
        data[i * 2] = (value and 0xFF).toByte()
        data[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + data.size)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(rate)
        putInt(rate * 2) // bytes a second
        putShort(2) // bytes a frame
        putShort(16) // bits
        put("data".toByteArray())
        putInt(data.size)
    }.array()
    runCatching { file.writeBytes(header + data) }
    return file
}