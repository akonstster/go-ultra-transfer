package app.goultra.transfer.ui

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** The whole frame, -1…1, sampled 0…1 (as the player's effects). */
private const val STEP_VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

/** To the screen: the picture's first row (texture t = 0) at the top. */
private const val SCREEN_VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, 0.5 - aFramePosition.y * 0.5);
}
"""

private const val COPY = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
}
"""

/**
 * The paused frame with the colour, drawn here rather than by the player: paused, the player draws no new frame, and
 * a frame caught half-way through its effects showed some steps with the old settings and some with the new (before /
 * after looked random). Here the whole chain of [steps] — the player's own objects, so the same settings — runs on
 * [frame] in one go whenever a setting changes ([LiveEdits]); the picture is fitted into the view like the player's.
 */
@OptIn(UnstableApi::class)
private class StillRenderer(private val frame: Bitmap, private val steps: List<ColorStep>, private val timeUs: Long) :
    GLSurfaceView.Renderer {
    private var input = 0
    private val targets = IntArray(2)
    private val buffers = IntArray(2)
    private var programs = emptyList<GlProgram>()
    private var resources = IntArray(0)
    private lateinit var screen: GlProgram
    private var viewW = 1
    private var viewH = 1

    private fun texture(): Int = IntArray(1).also { ids ->
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }[0]

    private fun program(vertex: String, fragment: String) = GlProgram(vertex, fragment).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        input = texture()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
        GLES20.glGenFramebuffers(2, buffers, 0)
        for (i in 0..1) {
            targets[i] = texture()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.width, frame.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targets[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        programs = steps.map { program(STEP_VERTEX, it.fragment) }
        resources = IntArray(steps.size) { steps[it].create() }
        screen = program(SCREEN_VERTEX, COPY)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Each step from the last result into the other buffer.
        var source = input
        steps.forEachIndexed { i, step ->
            val target = i % 2
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[target])
            GLES20.glViewport(0, 0, frame.width, frame.height)
            val p = programs[i]
            p.use()
            p.setSamplerTexIdUniform("uTexSampler", source, /* texUnitIndex= */ 0)
            step.bind(p, resources[i], frame.width, frame.height, timeUs)
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            source = targets[target]
        }
        // Fitted into the view, black around (as the player shows it).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val aspect = frame.width.toFloat() / frame.height
        val (w, h) = if (aspect > viewW.toFloat() / viewH) viewW to Math.round(viewW / aspect) else Math.round(viewH * aspect) to viewH
        GLES20.glViewport((viewW - w) / 2, (viewH - h) / 2, w, h)
        screen.use()
        screen.setSamplerTexIdUniform("uTexSampler", source, /* texUnitIndex= */ 0)
        screen.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/**
 * [frame] (the paused frame, as shot) through [steps] over the player, redrawn as soon as a setting moves; zoomed and
 * moved like the player's picture ([zoom], [shift]).
 */
@Composable
fun StillFrame(frame: Bitmap, steps: List<ColorStep>, timeUs: Long, zoom: Float, shift: Offset, modifier: Modifier = Modifier) {
    val view = remember(frame) { arrayOfNulls<GLSurfaceView>(1) }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                // Over the player's own surface.
                setZOrderMediaOverlay(true)
                setRenderer(StillRenderer(frame, steps, timeUs))
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                view[0] = this
            }
        },
        update = { v ->
            v.scaleX = zoom
            v.scaleY = zoom
            v.translationX = shift.x
            v.translationY = shift.y
        },
        modifier = modifier,
    )
    // A setting moved: draw again (a few times a frame at most).
    LaunchedEffect(frame) {
        var seen = -1
        while (true) {
            val now = LiveEdits.version
            if (now != seen) {
                seen = now
                view[0]?.requestRender()
            }
            delay(16)
        }
    }
}
