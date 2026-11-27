package app.goultra.transfer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.ProjectAudioDataSource
import app.goultra.transfer.camera.ProjectSound
import app.goultra.transfer.camera.audioRenderClips
import java.nio.ByteBuffer

/** One continuous audio source for the preview. Transport commands are owned by ProjectPreview. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun rememberProjectMusicPlayer(
    clips: List<ProjectAudioClip>, lengthMs: Long, grid: List<Long>, volume: Double, clickVolume: Double,
): ExoPlayer? {
    if (clips.isEmpty() || lengthMs <= 0L) return null
    val context = LocalContext.current
    val layout = remember(clips) { audioRenderClips(clips) }
    val beats = if (AppPrefs.metronome) grid else emptyList()
    val mix = BeatMix(beats, volume.toFloat().coerceIn(0f, 1f), clickVolume.toFloat().coerceIn(0f, 1f))
    val player = remember(context, layout, lengthMs, mix) {
        // A seek positions the WAV reader in project frames. Mix the click there, before the extractor can
        // retimestamp its buffers at the seek point. Both the music and the click then travel as the same PCM.
        val mixPreview: ((ByteBuffer, Long) -> Unit)? = if (beats.isEmpty() && mix.musicGain == 1f) null else {
            val click = beatClick(ProjectSound.RATE)
            fun(buffer: ByteBuffer, frame: Long) {
                mixBeatBuffer(buffer, frame, ProjectSound.RATE, ProjectSound.CHANNELS, false, click, mix)
            }
        }
        val source = ProgressiveMediaSource.Factory(
            DataSource.Factory { ProjectAudioDataSource(context.applicationContext, clips, lengthMs, mixPreview) },
            ExtractorsFactory { arrayOf(WavExtractor()) },
        ).setContinueLoadingCheckIntervalBytes(96_000)
            .createMediaSource(MediaItem.fromUri("project-audio:///mix.wav"))
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 8_000, 250, 750).build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            setMediaSource(source)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
