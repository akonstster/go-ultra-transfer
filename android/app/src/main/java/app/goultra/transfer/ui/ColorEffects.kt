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

/**
 * Exposure and saturation as one colour matrix that the player asks for on every frame: the sliders change [matrix]
 * and the picture follows at once, without rebuilding the effects.
 */
@OptIn(UnstableApi::class)
class LiveColorMatrix(ev: Float, saturation: Float) : RgbMatrix {
    @Volatile private var matrix = ColorMath.matrix(ev, saturation)

    fun set(ev: Float, saturation: Float) {
        matrix = ColorMath.matrix(ev, saturation)
    }

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

/**
 * Clipping stripes: red diagonal stripes where the white is blown (any channel ≥ [HIGH]), blue where the black is
 * crushed (all channels ≤ [LOW]). The effects see SDR video gamma-encoded, so these are about 250 and 5 of 255.
 */
@OptIn(UnstableApi::class)
class ClippingStripes : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram = GlProgram(VERTEX, FRAGMENT).apply {
            // The whole frame, -1…1 in x and y.
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
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

        const val VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        const val FRAGMENT = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float top = max(c.r, max(c.g, c.b));
  // Diagonal stripes 8 px wide, 16 px apart, fixed on the screen so they read as a marker, not as the picture.
  bool stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0) < 8.0;
  if (stripe && top >= $HIGH) c.rgb = vec3(1.0, 0.1, 0.1);
  else if (stripe && top <= $LOW) c.rgb = vec3(0.15, 0.4, 1.0);
  gl_FragColor = c;
}
"""
    }
}
