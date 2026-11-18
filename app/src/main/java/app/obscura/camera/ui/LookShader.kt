package app.obscura.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.Lut

/**
 * Live look on the viewfinder. Android 13+: an AGSL shader samples the look's 3D LUT on the
 * GPU (the same LUT the saved file uses) plus the vignette. Android 12: the closest colour
 * matrix. Grain is left out of the preview on purpose; it belongs to the final image.
 */
object LookShader {
    private const val TAG = "LookShader"

    // LUT texture layout: x = b * N + r, y = g. Linear filtering interpolates r and g; b is
    // interpolated by hand between two slices, so the lookup is fully trilinear.
    private const val AGSL = """
        uniform shader image;
        uniform shader lut;
        uniform float2 size;
        uniform float vignette;
        uniform float n;

        half4 main(float2 p) {
            half4 c = image.eval(p);
            float m = n - 1.0;
            float3 v = clamp(float3(c.rgb), 0.0, 1.0) * m;
            float b0 = floor(v.b);
            float b1 = min(b0 + 1.0, m);
            float fb = v.b - b0;
            half3 s0 = lut.eval(float2(b0 * n + v.r + 0.5, v.g + 0.5)).rgb;
            half3 s1 = lut.eval(float2(b1 * n + v.r + 0.5, v.g + 0.5)).rgb;
            half3 o = mix(s0, s1, half(fb));
            float2 d = (p / size - 0.5) * 2.0;
            float t = smoothstep(0.35, 1.6, dot(d, d));
            float vg = 1.0 - vignette * 0.6 * t;
            return half4(o * half(vg), c.a);
        }
    """

    private val textures = HashMap<String, Bitmap>()
    @Volatile private var shaderBroken = false

    fun effect(look: Look, width: Int, height: Int): RenderEffect? {
        if (look.isNeutral || Build.VERSION.SDK_INT < 31) return null
        if (Build.VERSION.SDK_INT >= 33 && !shaderBroken) {
            try {
                val shader = RuntimeShader(AGSL)
                val tex = textures.getOrPut(look.id) { texture(look.lut) }
                shader.setInputShader("lut", BitmapShader(tex, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                })
                shader.setFloatUniform("size", width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat())
                shader.setFloatUniform("vignette", look.spec.vignette)
                shader.setFloatUniform("n", look.lut.size.toFloat())
                return RenderEffect.createRuntimeShaderEffect(shader, "image")
            } catch (e: Throwable) {
                // A driver that rejects the shader gets the matrix preview instead, not a crash.
                Log.w(TAG, "AGSL look shader unavailable, using colour matrix", e)
                shaderBroken = true
            }
        }
        return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(look.fallbackMatrix)))
    }

    private fun texture(lut: Lut): Bitmap {
        val n = lut.size
        val px = IntArray(n * n * n)
        for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) px[g * (n * n) + b * n + r] = lut.argbAt(r, g, b)
        return Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
    }
}
