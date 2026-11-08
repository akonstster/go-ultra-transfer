package app.goultra.transfer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import app.goultra.transfer.camera.ProjectMusic
import kotlin.math.abs

/**
 * How far the music may stand from the project's own time, ms: within [MUSIC_DRIFT_MS] nothing is done, up to
 * [MUSIC_JUMP_MS] the music is eased back by its speed (a seek is a gap in the sound, and two players' clocks always
 * differ by a frame or two), past it — a real jump: a seek, another piece, a pause — it is put back by a seek.
 */
internal const val MUSIC_DRIFT_MS = 40L
internal const val MUSIC_JUMP_MS = 400L

/** The most the music's speed is moved to catch up: 3 % is not heard. */
private const val MUSIC_NUDGE = 0.03f

/**
 * The project's music in the preview: a player of its own, told one thing only — the project's time and whether it
 * plays (P2, `memory-bank/projects-plan.md`). The camera's Wi-Fi never holds it up: while a piece buffers or waits for
 * its file, the music goes on its own track; a card carried in the hand pauses the picture, and that pauses the music.
 *
 * A composition player would one day show the colour and the exact joins as well; this one is what a montage needs
 * now — the sound standing where the picture stands, whatever the picture is played with. Swapping the engine later
 * touches this file alone: its input is the two values below.
 */
@Composable
internal fun ProjectMusicSound(music: ProjectMusic?, timeMs: Long, playing: Boolean) {
    if (music == null) return
    val context = LocalContext.current
    val player = remember {
        // An mp3 seeks by an estimate unless it is told otherwise: with a variable bitrate that lands off the mark,
        // and the next check seeks again. Index seeking makes a seek land where it is asked to.
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            .setConstantBitrateSeekingEnabled(true)
        ExoPlayer.Builder(context, androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, extractors)).build().apply {
            playWhenReady = false
            volume = 0f
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // The track itself, put in once: another pick makes it another track.
    LaunchedEffect(music.uri) {
        player.setMediaItem(MediaItem.fromUri(music.uri))
        player.prepare()
    }
    LaunchedEffect(music.volume) { player.volume = music.volume.toFloat().coerceIn(0f, 1f) }
    // The one input: where the project is, and whether it plays. The track stands at its own offset + that time, playing
    // or not (a jump made while paused is still a jump), and it plays or pauses exactly as the picture does — a carried
    // card, or a piece waiting on the camera's Wi-Fi, stops both.
    LaunchedEffect(timeMs, playing, music.uri, music.offsetMs) {
        val want = (music.offsetMs + timeMs).coerceAtLeast(0L)
        val off = player.currentPosition - want
        val speed = musicSpeed(off, playing)
        if (speed == null) player.seekTo(want)
        if (player.playbackParameters.speed != (speed ?: 1f)) player.setPlaybackSpeed(speed ?: 1f)
        player.playWhenReady = playing
    }
}

/**
 * The music's speed for an offset of [offMs] from where it should be (positive: ahead): 1 when close enough, a little
 * slower or faster to catch up, null when only a seek will do (a jump, or anything at all while paused).
 */
internal fun musicSpeed(offMs: Long, playing: Boolean): Float? {
    val off = abs(offMs)
    return when {
        !playing -> if (off > MUSIC_DRIFT_MS) null else 1f
        off > MUSIC_JUMP_MS -> null
        off <= MUSIC_DRIFT_MS -> 1f
        // Ahead: a little slower; behind: a little faster. Three speeds only: a speed changed every check is heard.
        else -> if (offMs > 0) 1f - MUSIC_NUDGE else 1f + MUSIC_NUDGE
    }
}