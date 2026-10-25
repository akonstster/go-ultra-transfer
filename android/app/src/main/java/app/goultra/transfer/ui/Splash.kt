package app.goultra.transfer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

private val LetterGrey = Color(0xFFD3D4D4)
private val Ease = CubicBezierEasing(0.2f, 0f, 0.1f, 1f)

/**
 * Cold-start splash: the camera flies in from the depth while the viewer drifts backwards between huge «GO» and
 * «Ultra», which shrink into place beside it. Real perspective: an element at depth z is drawn scaled by 1/z and at
 * its position / z, so the letters start beside the eye (off-screen, giant) and settle at z = 1. Tap skips it.
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(2600, easing = LinearEasing))
        kotlinx.coroutines.delay(700)
        fade.animateTo(0f, tween(450))
        onDone()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.White)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
    ) {
        val w = maxWidth
        val t = Ease.transform(progress.value)
        val cameraSize = w * 0.30f
        val letterSize = with(LocalDensity.current) { (w * 0.115f).toSp() }
        // Final layout (offsets of element centres from the screen centre): letters level with the lens.
        val lineY = -cameraSize * 0.18f
        val goX = -w * 0.335f
        val ultraX = w * 0.32f

        // Letters: from z = 0.16 (right next to the eye) back to 1. Camera: from z = 3.5 (far away) to 1.
        val letterZ = lerp(0.16f, 1f, t)
        val cameraZ = lerp(3.5f, 1f, t)
        val letterAlpha = min(1f, progress.value / 0.12f)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProjectedLetters("GO", goX, lineY, letterZ, letterAlpha, letterSize)
            ProjectedLetters("Ultra", ultraX, lineY, letterZ, letterAlpha, letterSize)
            Box(
                Modifier.size(cameraSize).graphicsLayer {
                    val s = 1f / cameraZ
                    scaleX = s
                    scaleY = s
                    alpha = min(1f, progress.value / 0.25f)
                    rotationZ = lerp(-14f, -4f, t)
                },
            ) {
                CameraArt(
                    Modifier.fillMaxSize().shadow(18.dp, RoundedCornerShape(24), ambientColor = Color(0x33000000), spotColor = Color(0x44000000)),
                    withPod = false,
                )
            }
        }
    }
}

@Composable
private fun ProjectedLetters(text: String, x: Dp, y: Dp, z: Float, alpha: Float, size: androidx.compose.ui.unit.TextUnit) {
    val density = LocalDensity.current
    Text(
        text,
        color = LetterGrey,
        fontSize = size,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        softWrap = false,
        modifier = Modifier.graphicsLayer {
            val s = 1f / z
            // The element sits at (x, y); its projection is at (x / z, y / z) and scaled by 1 / z.
            val px = with(density) { x.toPx() }
            val py = with(density) { y.toPx() }
            translationX = px / z
            translationY = py / z
            scaleX = s
            scaleY = s
            this.alpha = alpha
        },
    )
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * max(0f, min(1f, t))
