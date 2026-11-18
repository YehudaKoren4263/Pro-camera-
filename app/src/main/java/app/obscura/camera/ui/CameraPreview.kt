package app.obscura.camera.ui

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.obscura.camera.camera.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Live viewfinder. The look runs on the GPU as a RenderEffect (API 31+), so what you see is
 * the colour you will get in the JPEG. The histogram samples the raw preview, before the look.
 */
/** View tag key for the effect state (look + retouch). */
private val R_KEY = "obscura-effect".hashCode() or 0x7F000000

@Composable
fun CameraPreview(
    look: Look,
    histogramOn: Boolean,
    onTexture: (SurfaceTexture) -> Unit,
    onTextureGone: () -> Unit,
    onHistogram: (Histogram) -> Unit,
    modifier: Modifier = Modifier,
    /** Small upright preview pixels for the composition coach; null = not needed. */
    onPixels: ((IntArray, Int, Int) -> Unit)? = null,
    /** Live skin retouch strength (0 = off). */
    retouch: Float = 0f,
) {
    var view by remember { mutableStateOf<TextureView?>(null) }
    val texture by rememberUpdatedState(onTexture)
    val gone by rememberUpdatedState(onTextureGone)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                // The shader's vignette needs the real size: re-apply when the layout changes.
                addOnLayoutChangeListener { v, l, t, r, btm, ol, ot, or, ob ->
                    if (Build.VERSION.SDK_INT >= 31 && (r - l != or - ol || btm - t != ob - ot)) {
                        val id = v.tag as? String ?: return@addOnLayoutChangeListener
                        app.obscura.camera.camera.Looks.ALL.firstOrNull { it.id == id }?.let { lk ->
                            (v as TextureView).setRenderEffect(LookShader.effect(lk, r - l, btm - t))
                        }
                    }
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = texture(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { gone(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                view = this
            }
        },
        update = { tv ->
            val key = look.id + "|" + retouch
            if (Build.VERSION.SDK_INT >= 31 && tv.getTag(R_KEY) != key) {
                tv.setTag(R_KEY, key)
                tv.tag = look.id
                tv.setRenderEffect(LookShader.effect(look, tv.width, tv.height, retouch))
            }
        },
    )

    val pixels by rememberUpdatedState(onPixels)
    LaunchedEffect(view, histogramOn, onPixels != null) {
        val tv = view ?: return@LaunchedEffect
        if (!histogramOn && pixels == null) return@LaunchedEffect
        while (isActive) {
            delay(180)
            if (!tv.isAvailable) continue
            val bmp = tv.getBitmap(96, 128) ?: continue
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val bw = bmp.width; val bh = bmp.height
            bmp.recycle()
            pixels?.let { cb -> withContext(Dispatchers.Default) { cb(px, bw, bh) } }
            if (histogramOn) onHistogram(withContext(Dispatchers.Default) { HistogramMath.compute(px) })
        }
    }
}
