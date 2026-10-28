package app.goultra.transfer.ui

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbMatrix
import app.goultra.transfer.camera.ColorMath
import app.goultra.transfer.camera.CubeLut
import app.goultra.transfer.camera.FilmMath
import app.goultra.transfer.camera.Look
import java.util.concurrent.atomic.AtomicInteger

/** The whole frame, -1…1 in x and y, sampled 0…1. */
private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

/**
 * Exposure and saturation as one colour matrix that the player asks for on every frame: the sliders change [matrix]
 * and the picture follows at once, without rebuilding the effects.
 */
@OptIn(UnstableApi::class)
class LiveColorMatrix(ev: Float, saturation: Float) : RgbMatrix {
    @Volatile private var matrix = ColorMath.matrix(ev, saturation)
    /** «До / после»: no change while set. */
    @Volatile var bypass = false

    fun set(ev: Float, saturation: Float) {
        matrix = ColorMath.matrix(ev, saturation)
    }

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = if (bypass) IDENTITY else matrix

    private companion object {
        val IDENTITY = ColorMath.matrix(0f, 1f)
    }
}

/**
 * Insta360's I-Log LUT as a live effect: the table in a texture (33 slices side by side, [CubeLut.atlas]), looked up
 * with blue between two slices and red / green by the texture's own filtering, mixed with the picture by [strength]
 * (0 = as shot). On / off and the strength are read on every frame — no rebuilding of the player's effects, which
 * stopped playback when the switch was turned.
 */
@OptIn(UnstableApi::class)
class LiveLut(private val lut: CubeLut, strength: Float) : GlEffect {
    @Volatile var strength = strength
    /** «До / после»: as shot while set. */
    @Volatile var bypass = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram = GlProgram(VERTEX, SHADER).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private val table: Int = IntArray(1).also { ids ->
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            val n = lut.size
            val pixels = java.nio.ByteBuffer.allocateDirect(n * n * n * 4).put(lut.atlas()).apply { position(0) }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }[0]

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setSamplerTexIdUniform("uLut", table, /* texUnitIndex= */ 1)
            glProgram.setFloatUniform("uSize", lut.size.toFloat())
            glProgram.setFloatUniform("uStrength", if (bypass) 0f else strength)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            glProgram.delete()
            GLES20.glDeleteTextures(1, intArrayOf(table), 0)
        }
    }

    private companion object {
        const val SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform float uSize;
uniform float uStrength;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 v = clamp(c.rgb, 0.0, 1.0);
  float n = uSize;
  float b = v.b * (n - 1.0);
  float b0 = floor(b);
  float b1 = min(b0 + 1.0, n - 1.0);
  // Texel centres: red across a slice, green down; never into the next slice.
  float x = v.r * (n - 1.0) + 0.5;
  float y = (v.g * (n - 1.0) + 0.5) / n;
  vec3 lo = texture2D(uLut, vec2((b0 * n + x) / (n * n), y)).rgb;
  vec3 hi = texture2D(uLut, vec2((b1 * n + x) / (n * n), y)).rgb;
  gl_FragColor = vec4(mix(c.rgb, mix(lo, hi, b - b0), uStrength), c.a);
}
"""
    }
}

/**
 * Sharpening as an unsharp mask on brightness only (no colour fringes): the picture minus a blur of [radius] px,
 * times [amount]. Small details below a fixed threshold (noise, compression) aren't boosted, and the boost is capped
 * so contours get no bright / dark halos. [amount] 0…1 and [radius] are read on every frame — sliders apply live.
 */
@OptIn(UnstableApi::class)
class Sharpen(amount: Float, radius: Float) : GlEffect {
    @Volatile var amount = amount
    @Volatile var radius = radius
    /** The preview can't keep up: one ring of taps instead of two. */
    @Volatile var light = false
    /** «До / после»: no sharpening while set. */
    @Volatile var bypass = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram = GlProgram(VERTEX, SHARPEN).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var texel = floatArrayOf(0f, 0f)

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texel = floatArrayOf(1f / inputWidth, 1f / inputHeight)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uTexel", texel)
            // Up to ×3 at the slider's end: strong, but the cap keeps it clean.
            glProgram.setFloatUniform("uAmount", if (bypass) 0f else amount * 3f)
            glProgram.setFloatUniform("uRadius", radius)
            glProgram.setFloatUniform("uLight", if (light) 1f else 0f)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            glProgram.delete()
        }
    }

    private companion object {
        // Blur: the pixel plus two rings of 8 taps at radius and 2 × radius (a rough Gaussian; light: the first only). Detail = brightness
        // minus blur; below 0.004 it is noise and stays as is; the boost is capped at ±0.08.
        const val SHARPEN = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uAmount;
uniform float uRadius;
uniform float uLight;
varying vec2 vTexSamplingCoord;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float l = luma(c.rgb);
  float sum = 4.0 * l;
  float weight = 4.0;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.7853982;
    vec2 d = vec2(cos(a), sin(a)) * uRadius * uTexel;
    sum += 2.0 * luma(texture2D(uTexSampler, vTexSamplingCoord + d).rgb);
    weight += 2.0;
    if (uLight < 0.5) {
      sum += luma(texture2D(uTexSampler, vTexSamplingCoord + 2.0 * d).rgb);
      weight += 1.0;
    }
  }
  float detail = l - sum / weight;
  detail = sign(detail) * max(abs(detail) - 0.004, 0.0);
  float boost = clamp(detail * uAmount, -0.08, 0.08);
  gl_FragColor = vec4(clamp(c.rgb + boost, 0.0, 1.0), c.a);
}
"""
    }
}

/**
 * Clipping stripes: red diagonal stripes where the white is blown (any channel ≥ [HIGH]), blue where the black is
 * crushed (all channels ≤ [LOW]). The effects see SDR video gamma-encoded, so these are about 250 and 5 of 255.
 */
@OptIn(UnstableApi::class)
class ClippingStripes : GlEffect {
    /** Shown or not, read on every frame (the effect stays in place either way). */
    @Volatile var on = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram = GlProgram(VERTEX, FRAGMENT).apply {
            // The whole frame, -1…1 in x and y.
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatUniform("uOn", if (on) 1f else 0f)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            glProgram.delete()
        }
    }

    private companion object {
        const val HIGH = "0.98"
        const val LOW = "0.02"

        const val FRAGMENT = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uOn;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float top = max(c.r, max(c.g, c.b));
  // Diagonal stripes 8 px wide, 16 px apart, fixed on the screen so they read as a marker, not as the picture.
  bool stripe = uOn > 0.5 && mod(gl_FragCoord.x + gl_FragCoord.y, 16.0) < 8.0;
  if (stripe && top >= $HIGH) c.rgb = vec3(1.0, 0.1, 0.1);
  else if (stripe && top <= $LOW) c.rgb = vec3(0.15, 0.4, 1.0);
  gl_FragColor = c;
}
"""
    }
}

/**
 * The effects for saving a video with its colour, in the order the viewer uses: Insta360's [lut] (an I-Log file with
 * the LUT on), exposure / saturation, sharpening, the «Стиль» look — only those that change something, in full: no
 * preview size, no clipping stripes. The sharpening radius was set on a preview of at most 1080 lines, so it is
 * widened by [sourceLines] / 1080 to look the same on the full picture (halation and grain are sized by the frame).
 */
@OptIn(UnstableApi::class)
fun exportEffects(lut: CubeLut?, look: ColorLook, sourceLines: Int): List<androidx.media3.common.Effect> {
    val out = mutableListOf<androidx.media3.common.Effect>()
    if (lut != null) out += LiveLut(lut, look.strength)
    if (look.exposure != 0f || look.saturation != 1f) out += LiveColorMatrix(look.exposure, look.saturation)
    if (look.sharpness > 0f) out += Sharpen(look.sharpness, look.sharpRadius * maxOf(1f, sourceLines / 1080f))
    if (look.hasStyle) out += FilmLook(look)
    return out
}

/**
 * «Стиль»: the grade of [FilmMath] (balance, flash, faces / skies, print curve, density, silver retention, colour per
 * hue, toning, skin) for the sliders and a preset ([Look]), plus halation — a red-orange glow around bright places, as
 * light scattered back through the film's layers — the preset's vignette, and grain: fine, moving with each frame,
 * strongest in the mid-tones, the way it shows on film. Every setting is read on each frame, so sliders apply live;
 * with everything at 0 the picture passes unchanged.
 */
@OptIn(UnstableApi::class)
class FilmLook(look: ColorLook) : GlEffect {
    @Volatile private var look = look
    @Volatile private var grade = gradeOf(look)
    /** The preview can't keep up: halation from one ring of taps instead of three. */
    @Volatile var light = false
    /** «До / после»: the picture as it comes in while set (frames still counted). */
    @Volatile var bypass = false
    /** Frames drawn so far: how fast the preview really goes. */
    val frames = AtomicInteger()

    fun set(look: ColorLook) {
        this.look = look
        grade = gradeOf(look)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram = GlProgram(VERTEX, FILM).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var texel = floatArrayOf(0f, 0f)
        private var grainScale = floatArrayOf(0f, 0f)

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texel = floatArrayOf(1f / inputWidth, 1f / inputHeight)
            // Grain cells of one pixel at 1080 lines, whatever the video's size: the same look on the preview and on 4K.
            grainScale = floatArrayOf(1080f * inputWidth / inputHeight, 1080f)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val off = bypass
            val l = look
            val g = if (off) NEUTRAL else grade
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uTexel", texel)
            glProgram.setFloatsUniform("uGrainScale", grainScale)
            glProgram.setFloatUniform("uFilm", g.film)
            glProgram.setFloatUniform("uSkin", g.skin)
            glProgram.setFloatsUniform("uGains", g.gains())
            glProgram.setFloatUniform("uFlash", g.flash)
            glProgram.setFloatUniform("uFaceLift", g.faceLift)
            glProgram.setFloatUniform("uSkyDark", g.skyDark)
            glProgram.setFloatUniform("uVignette", g.vignette)
            glProgram.setFloatUniform("uCurve", g.curveAmount())
            glProgram.setFloatUniform("uContrast", g.curveContrast())
            glProgram.setFloatUniform("uBleach", g.bleach)
            glProgram.setFloatUniform("uSat", g.sat)
            glProgram.setFloatsUniform("uHueSat", g.hueSat)
            glProgram.setFloatsUniform("uHueShift", FloatArray(4) { Math.toRadians(g.hueShift[it].toDouble()).toFloat() })
            glProgram.setFloatsUniform("uShadowTint", g.shadowTint)
            glProgram.setFloatsUniform("uMidTint", g.midTint)
            glProgram.setFloatsUniform("uHighTint", g.highTint)
            glProgram.setFloatUniform("uSkinGuard", g.skinGuard)
            glProgram.setFloatUniform("uGrain", if (off) 0f else l.grain)
            glProgram.setFloatUniform("uHalation", if (off) 0f else l.halation)
            glProgram.setFloatUniform("uLight", if (light) 1f else 0f)
            // A new grain pattern on each frame (about 30 a second), the same one for a paused frame.
            glProgram.setFloatUniform("uFrame", ((presentationTimeUs / 33_333) % 997).toFloat())
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frames.incrementAndGet()
        }

        override fun release() {
            super.release()
            glProgram.delete()
        }
    }

    private companion object {
        val NEUTRAL = FilmMath.Grade()

        fun gradeOf(look: ColorLook) = Look.grade(Look.of(look.preset), look.presetStrength, look.film, look.warmth, look.skin)

        private fun hue(i: Int) = Math.toRadians(FilmMath.HUES[i].toDouble()).toFloat()

        // The same steps and constants as FilmMath.apply; then halation (before the balance, in light, so the curve's
        // shoulder takes it), the vignette (with the faces / skies) and grain (last, on the finished picture).
        val FILM = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform vec2 uGrainScale;
uniform float uFilm;
uniform float uSkin;
uniform vec3 uGains;
uniform float uFlash;
uniform float uFaceLift;
uniform float uSkyDark;
uniform float uVignette;
uniform float uCurve;
uniform float uContrast;
uniform float uBleach;
uniform float uSat;
uniform vec4 uHueSat;
uniform vec4 uHueShift;
uniform vec2 uShadowTint;
uniform vec2 uMidTint;
uniform vec2 uHighTint;
uniform float uSkinGuard;
uniform float uGrain;
uniform float uHalation;
uniform float uFrame;
uniform float uLight;
varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float GAMMA = ${FilmMath.GAMMA};
const vec2 SKIN_LINE = vec2(${FilmMath.SKIN_CB}, ${FilmMath.SKIN_CR});
const vec4 HUES = vec4(${hue(0)}, ${hue(1)}, ${hue(2)}, ${hue(3)});

// Hash without sine (Dave Hoskins): even noise on mobile GPUs too.
float hash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

// Smooth noise on a grid: grain with soft edges, not single pixels.
float grainNoise(vec2 p, float frame) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash(vec3(i, frame));
  float b = hash(vec3(i + vec2(1.0, 0.0), frame));
  float c = hash(vec3(i + vec2(0.0, 1.0), frame));
  float d = hash(vec3(i + vec2(1.0, 1.0), frame));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) - 0.5;
}

float tone(float lin) {
  float stops = log2(max(lin, 1e-5) / ${FilmMath.GREY}) * uContrast;
  float y = ${FilmMath.GREY} * exp2(stops);
  if (y > ${FilmMath.KNEE}) y = ${FilmMath.KNEE} + (1.0 - ${FilmMath.KNEE}) * (1.0 - exp(-(y - ${FilmMath.KNEE}) / (1.0 - ${FilmMath.KNEE})));
  y = ${FilmMath.BLACK} * uCurve + (1.0 - ${FilmMath.BLACK} * uCurve) * y;
  return mix(lin, y, uCurve);
}

float skinMask(float luma, vec2 c) {
  float len = length(c);
  if (len < 1e-4) return 0.0;
  float cosine = dot(c, SKIN_LINE) / len;
  float rel = len / max(luma, 0.05);
  return smoothstep(0.707, 0.966, cosine) * smoothstep(0.04, 0.12, rel) * (1.0 - smoothstep(0.4, 0.75, rel)) *
    smoothstep(0.02, 0.1, luma) * (1.0 - smoothstep(0.85, 1.0, luma));
}

vec4 hueWeights(vec2 c) {
  float a = atan(c.y, c.x);
  vec4 w = max(cos(vec4(a) - HUES), 0.0);
  w = w * w;
  return w * w;
}

// How much a place glows: only near white.
float bright(vec2 at) {
  return smoothstep(0.75, 1.0, dot(texture2D(uTexSampler, at).rgb, LUMA));
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c0 = clamp(src.rgb, 0.0, 1.0);
  float y0 = dot(c0, LUMA);
  vec2 chroma0 = vec2((c0.b - y0) / 1.8556, (c0.r - y0) / 1.5748);
  float len0 = length(chroma0);
  float mask = skinMask(y0, chroma0);

  vec3 lin = pow(c0, vec3(GAMMA));

  // Halation: three rings of 8 taps out to 2.5 % of the frame's height (light: one ring), turned at random per pixel so
  // no star shows.
  if (uHalation > 0.0) {
    float radius = 0.025 / uTexel.y;
    float turn = hash(vec3(gl_FragCoord.xy, 7.0)) * 6.2831853;
    float glow = 0.0;
    for (int i = 0; i < 8; i++) {
      float a = turn + float(i) * 0.7853982;
      vec2 d = vec2(cos(a), sin(a)) * radius * uTexel;
      if (uLight > 0.5) {
        glow += bright(vTexSamplingCoord + 0.5 * d);
      } else {
        glow += 0.5 * bright(vTexSamplingCoord + 0.33 * d) + 0.33 * bright(vTexSamplingCoord + 0.67 * d) + 0.17 * bright(vTexSamplingCoord + d);
      }
    }
    glow /= 8.0;
    // Mostly around a bright place, less on it (it is white there anyway).
    float self = bright(vTexSamplingCoord);
    lin += glow * (1.0 - 0.6 * self) * uHalation * vec3(1.0, 0.28, 0.06);
  }

  lin = lin * uGains + uFlash;
  // Faces lighter, light skies darker, corners darker (stops).
  float sky = hueWeights(chroma0).w * smoothstep(0.02, 0.06, len0) * smoothstep(0.4, 0.8, y0);
  vec2 off = (vTexSamplingCoord - 0.5) * 2.0;
  float corner = smoothstep(0.35, 1.6, dot(off, off));
  lin *= exp2(uFaceLift * mask - uSkyDark * sky - uVignette * corner);

  if (uCurve > 0.0) lin = vec3(tone(lin.r), tone(lin.g), tone(lin.b));
  if (uFilm > 0.0) {
    float top = max(lin.r, max(lin.g, lin.b));
    float sat = top > 1e-5 ? (top - min(lin.r, min(lin.g, lin.b))) / top : 0.0;
    lin *= 1.0 - ${FilmMath.DENSITY} * uFilm * sat * sat;
  }
  vec3 g = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / GAMMA));

  // Silver retention: an overlay of the picture's brightness on it.
  if (uBleach > 0.0) {
    float l = dot(g, LUMA);
    vec3 o = l < 0.5 ? 2.0 * g * l : 1.0 - 2.0 * (1.0 - g) * (1.0 - l);
    g = mix(g, o, uBleach);
  }

  float y = dot(g, LUMA);
  vec2 chroma = vec2((g.b - y) / 1.8556, (g.r - y) / 1.5748);
  vec4 w = hueWeights(chroma);
  float satK = max(1.0 + dot(w, uHueSat - 1.0), 0.0) * uSat;
  float t = dot(w, uHueShift);
  chroma = vec2(chroma.x * cos(t) - chroma.y * sin(t), chroma.x * sin(t) + chroma.y * cos(t)) * satK;
  float shadows = smoothstep(0.0, 0.12, y) * (1.0 - smoothstep(0.12, 0.55, y));
  float mids = 4.0 * y * (1.0 - y);
  float highs = smoothstep(0.45, 0.8, y) * (1.0 - 0.7 * smoothstep(0.85, 1.0, y));
  vec2 toned = chroma + uFilm * (shadows * vec2(${FilmMath.SHADOW_CB}, ${FilmMath.SHADOW_CR}) + highs * vec2(${FilmMath.HIGH_CB}, ${FilmMath.HIGH_CR}))
    + shadows * uShadowTint + mids * uMidTint + highs * uHighTint;
  vec2 pulled = toned;
  if (len0 > 1e-5) pulled = mix(toned, length(chroma) * chroma0 / len0, uSkinGuard);
  float plen = length(pulled);
  float k = ${FilmMath.SKIN_PULL} * uSkin;
  if (plen > 1e-5 && k > 0.0) pulled = plen * normalize((1.0 - k) * pulled / plen + k * SKIN_LINE);
  chroma = mix(toned, pulled, mask);
  float r = y + 1.5748 * chroma.y;
  float b = y + 1.8556 * chroma.x;
  vec3 outc = vec3(r, (y - LUMA.r * r - LUMA.b * b) / LUMA.g, b);

  // Grain: two sizes of noise, more in the mid-tones than in black and white.
  if (uGrain > 0.0) {
    vec2 p = vTexSamplingCoord * uGrainScale;
    float n = 0.65 * grainNoise(p, uFrame) + 0.35 * grainNoise(p * 0.5 + 17.0, uFrame);
    float yl = clamp(y, 0.0, 1.0);
    outc += n * uGrain * 0.14 * (0.25 + 3.0 * yl * (1.0 - yl));
  }
  gl_FragColor = vec4(clamp(outc, 0.0, 1.0), src.a);
}
"""
    }
}
