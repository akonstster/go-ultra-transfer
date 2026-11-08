package app.goultra.transfer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.goultra.transfer.camera.ProjectMusic
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the click sounds, and how quickly it dies away — a tick, not a note. */
private const val CLICK_MS = 25
private const val CLICK_HZ = 1_400.0

/**
 * The click on the grid (P3): every beat of the track sounds where its red line stands, so a grid found by the analysis
 * or set by hand can be *heard*, not only seen. On every beat of the project's own clock, no accents — which beat of the
 * bar it is, is not something the analysis knows, and a guessed accent would be a lie.
 *
 * Its time is the **music's own** ([MusicClock]), not the picture's: a piece waiting on the camera's Wi-Fi stalls the
 * picture while the music plays on, and a click that followed the picture walked off the sound — the desync that was
 * heard. Within a tick the time is counted from the monotonic clock ([MetronomeSchedule]), so a beat that falls between
 * two ticks is clicked on itself; the click is queued as long before its beat as the phone takes to sound it. One beat,
 * one click, and a jump of the clock takes the clicks it promised with it.
 */
@Composable
internal fun ProjectMetronome(music: ProjectMusic?, timeMs: Long, playing: Boolean, heard: MusicClock) {
    val beats = music?.beats.orEmpty()
    if (music == null || beats.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val click = remember { Click(context) }
    DisposableEffect(click) { onDispose { click.release() } }
    // What the phone takes to sound a click: the click must be *heard* on the beat, so it is queued that much earlier.
    val latency = remember(click) { outputLatencyMs(context) }
    val schedule = remember(beats, music.offsetMs) { MetronomeSchedule(beats, music.offsetMs) }
    var pending by remember(schedule) { mutableStateOf<Job?>(null) }
    val on = AppPrefs.metronome
    LaunchedEffect(schedule, timeMs, playing, on) {
        val at = SystemClock.elapsedRealtime()
        // Where the click is due is where the *music* stands; the picture's own clock is only the stand-in for a track that
        // is not heard yet (still loading, or the film has not reached its start).
        val base = heard.now(at) ?: timeMs
        if (schedule.told(base, at, playing && on)) {
            pending?.cancel()
            pending = null
        }
        if (!playing || !on) return@LaunchedEffect
        val due = schedule.due(at, CLICK_LEAD_MS, latency) ?: return@LaunchedEffect
        if (due.inMs <= 0L) {
            // On the beat: the click goes at once, and only once (a tick that comes late does not repeat it).
            if (click.play()) schedule.fired(due.beat) else schedule.released()
            return@LaunchedEffect
        }
        // A beat a moment away: waited for, so the click is not up to a tick early or late.
        pending?.cancel()
        pending = scope.launch {
            delay(due.inMs)
            if (click.play()) schedule.fired(due.beat) else schedule.released()
        }
    }
}

/**
 * What the phone adds between a click put on the queue and being heard, ms: its own output buffer, a frame per sample of
 * its own output rate. It is not the whole truth (a Bluetooth headset adds tens of ms more, and nothing on the phone can
 * know it), but it is the part the phone does say — and a click queued that much before its beat is heard nearer the
 * line than one queued on it.
 */
private fun outputLatencyMs(context: Context): Long {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return CLICK_LATENCY_MS
    val frames = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toLongOrNull()
    val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toLongOrNull()
    if (frames == null || rate == null || frames <= 0L || rate <= 0L) return CLICK_LATENCY_MS
    return (frames * 1000 / rate).coerceIn(0L, 200L)
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
    /** Whether the tick is in the pool yet: a click asked for before that would simply not be heard. */
    @Volatile
    private var ready = false
    private val id: Int

    init {
        // The listener goes on before the load: a small file may be in the pool before a listener added afterwards.
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        id = pool.load(clickFile(context).absolutePath, 1)
    }

    /** The click, if it can sound at all: false when the tick is still loading or the pool refuses (the beat is not lost). */
    fun play(): Boolean {
        if (!ready) return false
        return pool.play(id, 1f, 1f, 1, 0, 1f) != 0
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