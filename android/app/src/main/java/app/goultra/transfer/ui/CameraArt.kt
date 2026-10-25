package app.goultra.transfer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * The GO Ultra drawn from shapes (no product photos): the camera module alone, or seated in the Action Pod
 * (grip panel, orange button). [blinking] makes the turquoise status LED pulse — «connecting».
 */
@Composable
fun CameraArt(modifier: Modifier = Modifier, withPod: Boolean = true, blinking: Boolean = false) {
    val led = if (blinking) {
        val pulse by rememberInfiniteTransition(label = "led").animateFloat(
            0.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "led",
        )
        pulse
    } else {
        1f
    }
    Canvas(modifier.aspectRatio(if (withPod) 1.53f else 1f)) {
        if (withPod) drawPod(led) else drawModule(Offset.Zero, size.minDimension, led)
    }
}

private fun DrawScope.drawPod(led: Float) {
    val w = size.width
    val h = size.height
    // Pod body.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF7F7F8), Color(0xFFE4E5E7))),
        cornerRadius = CornerRadius(h * 0.16f),
    )
    drawRoundRect(Color(0x1A000000), cornerRadius = CornerRadius(h * 0.16f), style = Stroke(h * 0.006f))
    // Grip panel with a leather-like dot texture and the vertical label.
    val gripLeft = w * 0.075f
    val gripTop = h * 0.06f
    val gripSize = Size(w * 0.215f, h * 0.86f)
    drawRoundRect(Color(0xFFE6E7E8), topLeft = Offset(gripLeft, gripTop), size = gripSize, cornerRadius = CornerRadius(h * 0.08f))
    var y = gripTop + h * 0.03f
    var row = 0
    while (y < gripTop + gripSize.height - h * 0.02f) {
        var x = gripLeft + h * 0.025f + (if (row % 2 == 0) 0f else h * 0.012f)
        while (x < gripLeft + gripSize.width - h * 0.015f) {
            drawCircle(Color(0x0F000000), radius = h * 0.004f, center = Offset(x, y))
            x += h * 0.024f
        }
        y += h * 0.022f
        row++
    }
    val label = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color(0xFFB9BBBE).toArgb()
        textSize = h * 0.075f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val labelCenter = Offset(gripLeft + gripSize.width * 0.62f, gripTop + gripSize.height * 0.62f)
    rotate(-90f, labelCenter) {
        drawContext.canvas.nativeCanvas.drawText("GO Ultra", labelCenter.x, labelCenter.y + label.textSize / 3, label)
    }
    // Orange button on the right edge.
    drawRoundRect(Color(0xFFF07A2E), topLeft = Offset(w * 0.985f, h * 0.40f), size = Size(w * 0.02f, h * 0.13f), cornerRadius = CornerRadius(w * 0.01f))
    // Cradle rim and the camera module.
    val moduleSide = h * 0.88f
    val moduleTopLeft = Offset(w * 0.36f, h * 0.05f)
    drawRoundRect(
        Color(0xFFCFD1D4),
        topLeft = moduleTopLeft - Offset(h * 0.025f, h * 0.02f),
        size = Size(moduleSide + h * 0.05f, moduleSide + h * 0.045f),
        cornerRadius = CornerRadius(moduleSide * 0.26f),
    )
    drawModule(moduleTopLeft, moduleSide, led)
}

/** Camera module of side [s] at [topLeft]: white rounded body, big lens top-right, mic grille, LED. */
internal fun DrawScope.drawModule(topLeft: Offset, s: Float, led: Float) {
    val corner = CornerRadius(s * 0.24f)
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White, Color(0xFFECEDEE)), start = topLeft, end = topLeft + Offset(s, s)),
        topLeft = topLeft,
        size = Size(s, s),
        cornerRadius = corner,
    )
    drawRoundRect(Color(0x14000000), topLeft = topLeft, size = Size(s, s), cornerRadius = corner, style = Stroke(s * 0.008f))
    lens(topLeft + Offset(s * 0.64f, s * 0.36f), s * 0.31f)
    micGrille(topLeft + Offset(s * 0.21f, s * 0.29f), s * 0.07f)
    drawCircle(Color(0xFFDADCDD), radius = s * 0.025f, center = topLeft + Offset(s * 0.21f, s * 0.46f))
    // Status LED with a soft glow.
    val ledSize = Size(s * 0.11f, s * 0.04f)
    val ledTopLeft = topLeft + Offset(s * 0.70f - ledSize.width / 2, s * 0.80f)
    drawRoundRect(
        Color(0xFF4FE6EC).copy(alpha = 0.4f * led),
        topLeft = ledTopLeft - Offset(s * 0.012f, s * 0.012f),
        size = Size(ledSize.width + s * 0.024f, ledSize.height + s * 0.024f),
        cornerRadius = CornerRadius(s * 0.03f),
    )
    drawRoundRect(Color(0xFF41DCE3).copy(alpha = 0.35f + 0.65f * led), topLeft = ledTopLeft, size = ledSize, cornerRadius = CornerRadius(s * 0.02f))
}

private fun DrawScope.lens(c: Offset, r: Float) {
    drawCircle(Color(0xFF15171A), radius = r, center = c) // outer bezel
    drawCircle(Color(0xFF3A3D42), radius = r * 0.96f, center = c, style = Stroke(r * 0.03f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFF2B3B44), Color(0xFF0A0D10)), center = c + Offset(-r * 0.15f, -r * 0.15f), radius = r * 0.9f),
        radius = r * 0.80f, center = c,
    )
    // Coloured coating reflections.
    drawArc(Color(0x6670C46A), 200f, 70f, false, topLeft = c - Offset(r * 0.66f, r * 0.66f), size = Size(r * 1.32f, r * 1.32f), style = Stroke(r * 0.08f))
    drawArc(Color(0x55A070D0), 20f, 60f, false, topLeft = c - Offset(r * 0.60f, r * 0.60f), size = Size(r * 1.2f, r * 1.2f), style = Stroke(r * 0.06f))
    drawCircle(Color(0xFF05070A), radius = r * 0.36f, center = c)
    drawCircle(Color(0xFF2F3A42), radius = r * 0.36f, center = c, style = Stroke(r * 0.04f))
    drawCircle(Color(0xFF0E1A22), radius = r * 0.20f, center = c)
    // Highlights.
    drawCircle(Color(0xCCFFFFFF), radius = r * 0.07f, center = c + Offset(-r * 0.30f, -r * 0.32f))
    drawCircle(Color(0x66FFFFFF), radius = r * 0.035f, center = c + Offset(r * 0.12f, -r * 0.05f))
}

private fun DrawScope.micGrille(c: Offset, r: Float) {
    drawCircle(Color(0xFFE6E7E8), radius = r * 1.15f, center = c)
    val step = r / 2.6f
    var y = -r
    while (y <= r) {
        var x = -r
        while (x <= r) {
            if (x * x + y * y <= r * r) drawCircle(Color(0xFFA9ACAF), radius = step * 0.28f, center = c + Offset(x, y))
            x += step
        }
        y += step
    }
}
