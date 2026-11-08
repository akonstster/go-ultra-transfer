package app.goultra.transfer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.goultra.transfer.camera.ProjectMusic
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long the click sounds, and how quickly it dies away — a tick, not a note. */
private const val CLICK_MS = 25
private const val CLICK_HZ = 1_400.0

/**
 * What SoundPool itself holds before a click reaches the mixer, ms: its own sample buffers and the sonification track it
 * plays them through. No phone tells it; this is the allowance that took the click from "after the beat" to the beat.
 */
private const val CLICK_QUEUE_MS = 30L

/**
 * What the click keeps time with, read afresh by the one scheduler loop: the project's moment, whether it plays, whether
 * the metronome is on, and the two numbers of the timing — the phone's own delay and the ear's signed shift.
 */
private data class ClickInput(
    val timeMs: Long,
    /** When the reading was taken: if the main thread stalls, the picture's clock still has to run on here. */
    val atMs: Long,
    val playing: Boolean,
    val on: Boolean,
    val latencyMs: Long,
    val shiftMs: Long,
)

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
internal fun ProjectMetronome(
    music: ProjectMusic?,
    timeMs: Long,
    playing: Boolean,
    heard: MusicClock,
    /** The app's own log: what the phone says of its sound path, so a nudge that was needed is on record. */
    onLog: (String) -> Unit = {},
) {
    val beats = music?.beats.orEmpty()
    if (music == null || beats.isEmpty()) return
    val context = LocalContext.current
    val click = remember { Click(context) }
    // The scheduler gets a thread of its own at the audio priority: a Compose frame that takes 200 ms must not take the
    // next click with it. The thread is made once with the click and closed with it.
    val scheduler = remember {
        Executors.newSingleThreadExecutor { work ->
            Thread(work, "metronome").apply { priority = Thread.MAX_PRIORITY }
        }.asCoroutineDispatcher()
    }
    DisposableEffect(click, scheduler) {
        onDispose {
            scheduler.close()
            click.release()
        }
    }
    // What the phone takes to sound a click: the click must be *heard* on the beat, so it is queued that much earlier.
    val latency = remember(click) { outputLatencyMs(context) }
    // The ear may take the last few tenths either way (no phone tells how long its mixer holds a click).
    val nudge = AppPrefs.clickNudge
    val log = rememberUpdatedState(onLog)
    LaunchedEffect(latency, nudge) {
        val where = when {
            nudge > 0 -> "позже"
            nudge < 0 -> "раньше"
            else -> "по биту"
        }
        val signed = if (nudge > 0) "+$nudge" else "$nudge"
        log.value("клик: задержка вывода $latency мс, сдвиг $signed мс ($where)")
    }
    val on = AppPrefs.metronome
    val schedule = remember(beats, music.offsetMs) { MetronomeSchedule(beats, music.offsetMs) }
    // One scheduler lives through the whole playback. The main thread only hands it readings; it never waits for a beat
    // itself, so a stalled composition cannot delay the SoundPool call. Readings are conflated: only the newest matters.
    val updates = remember(schedule) { Channel<ClickInput>(Channel.CONFLATED) }
    val notes = remember(schedule) { Channel<String>(Channel.UNLIMITED) }
    LaunchedEffect(notes) {
        for (note in notes) log.value(note)
    }
    // Every tick of the project's clock hands over a fresh reading: a beat may have come due, the music may have paused,
    // or the clock may have jumped. The reading carries its own monotonic moment, so extrapolation survives a stall too.
    LaunchedEffect(updates, timeMs, playing, on, latency, nudge) {
        updates.trySend(
            ClickInput(timeMs, SystemClock.elapsedRealtime(), playing, on, latency, nudge.toLong()),
        )
    }
    LaunchedEffect(schedule, click, updates, notes, scheduler) {
        withContext(scheduler) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            var waitingBeat = Long.MIN_VALUE
            var targetAt = 0L
            var waitedShift = 0L
            var waitedLatency = 0L
            var complained = false
            var latest: ClickInput? = null
            // The newest reading that arrived while the scheduler was busy; older ones are already useless.
            fun newest(): ClickInput? {
                while (true) latest = updates.tryReceive().getOrNull() ?: break
                return latest
            }
            fun play(beat: Long): Boolean {
                val ok = click.play()
                if (!ok && !complained) {
                    notes.trySend("клик: SoundPool не принял удар $beat (ещё загружается или отказал)")
                    complained = true
                }
                if (ok) complained = false
                return ok
            }
            while (isActive) {
            val now = newest()
                if (now == null) {
                    // Nothing heard yet (the first moment after the sound was picked): wait for a reading, not for a beat.
                    latest = updates.receive()
                    continue
                }
                val at = SystemClock.elapsedRealtime()
                // Where the click is due is where the *music* stands; the picture's own clock is only the stand-in for a
                // track that is not heard yet (still loading, or the film has not reached its start). The stand-in runs
                // from the reading's own monotonic moment, so a main-thread stall does not freeze it.
                val picture = if (now.playing) now.timeMs + (at - now.atMs).coerceAtLeast(0L) else now.timeMs
                val base = heard.now(at) ?: picture
                val lateBefore = schedule.lateBeat
                if (schedule.told(base, at, now.playing && now.on)) {
                    if (waitingBeat != Long.MIN_VALUE) {
                        notes.trySend("клик: часы сдвинулись — удар $waitingBeat снят")
                    }
                    waitingBeat = Long.MIN_VALUE
                }
                if (!now.playing || !now.on) {
                    waitingBeat = Long.MIN_VALUE
                    latest = updates.receive()
                    continue
                }
                if (waitingBeat != Long.MIN_VALUE && (now.shiftMs != waitedShift || now.latencyMs != waitedLatency)) {
                    // The ear moved the click: put the same beat back into the plan with its new moment, never a second.
                    schedule.released()
                    waitingBeat = Long.MIN_VALUE
                    continue
                }
                if (waitingBeat != Long.MIN_VALUE) {
                    val left = targetAt - at
                    if (left > 0L) {
                        // Wait for the moment, or for a tick that may have paused the music or moved the click. The wait
                        // lives on this thread, not in Compose: a postponed frame changes nothing here.
                        withTimeoutOrNull(left) { updates.receive() }?.let { latest = it }
                        continue
                    }
                    if (play(waitingBeat)) schedule.fired(waitingBeat) else schedule.released()
                    waitingBeat = Long.MIN_VALUE
                    continue
                }
                val due = schedule.due(at, CLICK_LEAD_MS, now.latencyMs, now.shiftMs)
                if (schedule.lateBeat != lateBefore) {
                    notes.trySend("клик: удар ${schedule.lateBeat} пропущен — опоздал на ${schedule.lateByMs} мс")
                }
                if (due == null) {
                    latest = updates.receive()
                    continue
                }
                if (due.inMs <= 0L) {
                    // On the beat: the click goes at once, and only once (a tick that comes late does not repeat it).
                    if (play(due.beat)) {
                        schedule.fired(due.beat)
                    } else {
                        schedule.released()
                        // Not in the pool yet: give the load a moment instead of spinning on the same beat.
                        withTimeoutOrNull(20L) { updates.receive() }?.let { latest = it }
                    }
                    continue
                }
                waitingBeat = due.beat
                targetAt = at + due.inMs
                waitedShift = now.shiftMs
                waitedLatency = now.latencyMs
            }
        }
    }
}

/**
 * What the phone adds between a click put on the queue and being heard, ms: its own buffer, a frame per sample of its own
 * output rate, and [CLICK_QUEUE_MS] for what SoundPool itself holds before the click reaches the mixer. The phone tells
 * the buffer and nothing else — a click queued exactly on the beat was heard *after* it, which is the lag that was left —
 * so the rest is a fair allowance, and the ear takes the last tenths with the nudge in the Tempo sheet.
 */
private fun outputLatencyMs(context: Context): Long {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return CLICK_LATENCY_MS
    val frames = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toLongOrNull()
    val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toLongOrNull()
    val buffer = if (frames == null || rate == null || frames <= 0L || rate <= 0L) 0L
    else (frames * 1000 / rate).coerceIn(0L, 200L)
    return (buffer + CLICK_QUEUE_MS).coerceIn(0L, 300L)
}

/** The tick itself: a short, quickly dying tone, made once and kept in the cache — a metronome must sound the same always. */
private class Click(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
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
    /** The scheduler runs on its own thread and may outlive the composition by one loop: never play into a closed pool. */
    @Volatile
    private var released = false
    private val id: Int

    init {
        // The listener goes on before the load: a small file may be in the pool before a listener added afterwards.
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        id = pool.load(clickFile(context).absolutePath, 1)
    }

    /** The click, if it can sound at all: false when the tick is still loading or the pool refuses (the beat is not lost). */
    fun play(): Boolean {
        if (!ready || released) return false
        return pool.play(id, 1f, 1f, 1, 0, 1f) != 0
    }

    fun release() {
        released = true
        pool.release()
    }
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