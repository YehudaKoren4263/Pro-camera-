package app.obscura.camera.time

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image

/** Android glue for the time engine: camera images in, bitmaps out. */
object YuvImages {

    /** Copies a YUV_420_888 image into tight planes, whatever its row and pixel strides. */
    fun copy(img: Image): YuvFrame {
        val w = img.width and 1.inv(); val h = img.height and 1.inv()
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val y = ByteArray(w * h)
        val yb = yp.buffer; val yRow = yp.rowStride
        if (yp.pixelStride == 1) {
            for (r in 0 until h) { yb.position(r * yRow); yb.get(y, r * w, w) }
        } else {
            for (r in 0 until h) for (c in 0 until w) y[r * w + c] = yb.get(r * yRow + c * yp.pixelStride)
        }
        val cw = w / 2; val ch = h / 2
        val u = ByteArray(cw * ch); val v = ByteArray(cw * ch)
        val ub = up.buffer; val vb = vp.buffer
        val uRow = up.rowStride; val vRow = vp.rowStride; val uPix = up.pixelStride; val vPix = vp.pixelStride
        for (r in 0 until ch) {
            val uo = r * uRow; val vo = r * vRow; val o = r * cw
            for (c in 0 until cw) {
                u[o + c] = ub.get(uo + c * uPix)
                v[o + c] = vb.get(vo + c * vPix)
            }
        }
        return YuvFrame(w, h, y, u, v)
    }

    /** Converts to an upright ARGB bitmap, rotated by the capture orientation. */
    fun toBitmap(f: YuvFrame, rotationDegrees: Int): Bitmap {
        val bmp = Bitmap.createBitmap(Yuv.toArgb(f), f.w, f.h, Bitmap.Config.ARGB_8888)
        if (rotationDegrees % 360 == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        bmp.recycle()
        return rotated
    }
}
