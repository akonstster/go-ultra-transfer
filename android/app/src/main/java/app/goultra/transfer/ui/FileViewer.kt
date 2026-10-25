package app.goultra.transfer.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.MediaKind

/**
 * Quick look at a file that is still on the camera: video plays its low-res LRV copy (or the file itself if there is
 * none) in the built-in player, photos open full size with pinch zoom. «Скачать» takes just this file.
 */
@Composable
fun FileViewer(vm: CameraController, file: CameraFile, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (file.kind == MediaKind.VIDEO) VideoPreview(vm, file) else PhotoPreview(vm, file)
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).safeDrawingPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClose) { Icon(Icons.Filled.Close, tr("Закрыть", "Close"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (file.kind == MediaKind.VIDEO && vm.previewUrl(file).endsWith(".lrv")) {
                            tr("с камеры · низкое качество", "from the camera · low quality")
                        } else {
                            tr("с камеры", "from the camera")
                        },
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (file.name !in vm.downloaded) {
                    Button({ vm.downloadOne(file); onClose() }, enabled = !vm.isDownloading) { Text(tr("Скачать", "Download")) }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(vm: CameraController, file: CameraFile) {
    val context = LocalContext.current
    val source = remember { vm.previewSource() }
    if (source == null) {
        Text(tr("Нет связи с камерой по Wi-Fi", "No Wi-Fi link to the camera"), color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    val player = remember(file.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(vm.previewUrl(file))))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PhotoPreview(vm: CameraController, file: CameraFile) {
    val config = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) { (maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 1.5f).toInt() }
    val image by produceState<ImageBitmap?>(null, file.uri) { value = vm.previewPhoto(file, maxSide) }
    var loaded by remember(file.uri) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = image
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            loaded = true
            Image(
                bmp,
                file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        // Double tap: 1x ↔ 2.5x.
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .transformable(zoom)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}
