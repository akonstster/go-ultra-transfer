package app.goultra.transfer.ui

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig as EGLConfig14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private class StillRenderer(first: Bitmap, private val steps: List<ColorStep>, @Volatile var timeUs: Long) :
    GLSurfaceView.Renderer {
    /** The frame to show; a new one (after a seek) replaces the old in the same view. */
    @Volatile var next: Bitmap = first
    private var frame: Bitmap = first
    private var uploaded: Bitmap? = null
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

    /** [next] into the input texture, the two buffers sized to it. */
    private fun upload() {
        val f = next
        if (f === uploaded) return
        val resize = uploaded?.let { it.width != f.width || it.height != f.height } ?: true
        frame = f
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, f, 0)
        if (resize) {
            for (i in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets[i])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, f.width, f.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targets[i], 0)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        uploaded = f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context (first, or after the surface was lost): everything made again.
        uploaded = null
        input = texture()
        GLES20.glGenFramebuffers(2, buffers, 0)
        for (i in 0..1) targets[i] = texture()
        programs = steps.map { program(STEP_VERTEX, it.fragment) }
        resources = IntArray(steps.size) { steps[it].create() }
        screen = program(SCREEN_VERTEX, COPY)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        upload()
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
 * [frame] (ARGB_8888, mutable) through [steps] off screen, the result written back into it: the very chain the still
 * draws, for saving a photo with its colour. Its own EGL context on the calling (background) thread.
 */
@OptIn(UnstableApi::class)
internal fun renderStepsInto(frame: Bitmap, steps: List<ColorStep>, timeUs: Long = 0) {
    require(frame.config == Bitmap.Config.ARGB_8888 && frame.isMutable) { "нужен изменяемый ARGB_8888" }
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(display != EGL14.EGL_NO_DISPLAY) { "нет EGL" }
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL не запустился" }
    val attributes = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
    )
    val configs = arrayOfNulls<EGLConfig14>(1)
    val count = IntArray(1)
    check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "нет конфигурации EGL" }
    val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    check(context != EGL14.EGL_NO_CONTEXT) { "нет контекста EGL" }
    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    try {
        check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL не включился" }
        val max = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        check(frame.width <= max && frame.height <= max) { "фото больше, чем умеет видеокарта ($max px)" }
        val ids = IntArray(3)
        GLES20.glGenTextures(3, ids, 0)
        val buffers = IntArray(2)
        GLES20.glGenFramebuffers(2, buffers, 0)
        for (id in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[i + 1])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.width, frame.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[i + 1], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "буфер кадра не готов" }
        }
        val programs = steps.map { stepProgram(STEP_VERTEX, it.fragment) }
        val resources = IntArray(steps.size) { steps[it].create() }
        try {
            var source = ids[0]
            var last = -1
            steps.forEachIndexed { i, step ->
                last = i % 2
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[last])
                GLES20.glViewport(0, 0, frame.width, frame.height)
                val p = programs[i]
                p.use()
                p.setSamplerTexIdUniform("uTexSampler", source, /* texUnitIndex= */ 0)
                step.bind(p, resources[i], frame.width, frame.height, timeUs)
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                source = ids[last + 1]
            }
            if (last >= 0) {
                // Row 0 of the result is row 0 of the picture (sampled as uploaded): no flip.
                val pixels = java.nio.ByteBuffer.allocateDirect(frame.width * frame.height * 4).order(java.nio.ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, frame.width, frame.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "видеокарта не отдала картинку" }
                pixels.rewind()
                frame.copyPixelsFromBuffer(pixels)
            }
        } finally {
            steps.forEachIndexed { i, step -> step.destroy(resources[i]) }
            programs.forEach { it.delete() }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(2, buffers, 0)
            GLES20.glDeleteTextures(3, ids, 0)
        }
    } finally {
        // The default display is shared with the views' and the player's contexts: released, not terminated.
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}

@OptIn(UnstableApi::class)
private fun stepProgram(vertex: String, fragment: String) = GlProgram(vertex, fragment).apply {
    setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
}

/**
 * [frame] (the paused frame, as shot) through [steps] over the player, redrawn when a setting moves ([LiveEdits]) or a
 * new frame comes (one view for the whole pause); zoomed and moved like the player's picture ([zoom], [shift]).
 */
@Composable
fun StillFrame(frame: Bitmap, steps: List<ColorStep>, timeUs: Long, zoom: Float, shift: Offset, modifier: Modifier = Modifier) {
    val renderer = remember { StillRenderer(frame, steps, timeUs) }
    val view = remember { arrayOfNulls<GLSurfaceView>(1) }
    var settle by remember { mutableIntStateOf(0) }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                // Over the player's own surface.
                setZOrderMediaOverlay(true)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                view[0] = this
            }
        },
        update = { v ->
            if (renderer.next !== frame || renderer.timeUs != timeUs) {
                renderer.next = frame
                renderer.timeUs = timeUs
                v.requestRender()
            }
            v.scaleX = zoom
            v.scaleY = zoom
            v.translationX = shift.x
            v.translationY = shift.y
        },
        modifier = modifier.drawWithContent { settle; drawContent() },
    )
    // A setting moved: draw again. And a few times as the view settles: the SurfaceView cuts its hole in the window
    // only when drawn after its first frame, and inside Compose nothing redraws it then (Huawei: a black still until
    // the first change) — so the view is invalidated and the Compose layer redrawn too.
    LaunchedEffect(Unit) {
        launch {
            for (wait in longArrayOf(100, 250, 500, 1000)) {
                delay(wait)
                view[0]?.apply { requestRender(); invalidate() }
                settle++
            }
        }
        LiveEdits.version.collect { view[0]?.requestRender() }
    }
}
