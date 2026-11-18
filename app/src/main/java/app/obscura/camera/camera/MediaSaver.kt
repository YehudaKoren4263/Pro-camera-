package app.obscura.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object MediaSaver {
    private const val FOLDER = "DCIM/Obscura"

    /** Saves the JPEG. A non-neutral look is baked in; EXIF from the camera is preserved. */
    fun saveJpeg(context: Context, jpeg: ByteArray, look: Look, name: String): Uri? {
        val bytes = if (look.isNeutral) jpeg else applyLook(jpeg, look)
        val uri = insert(context, "$name.jpg", "image/jpeg") ?: return null
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (!look.isNeutral) copyExif(context, jpeg, uri, look)
        publish(context, uri)
        return uri
    }

    /** Writes sensor data untouched. No look, no processing. */
    fun saveDng(
        context: Context, characteristics: CameraCharacteristics, result: TotalCaptureResult,
        image: Image, orientationDegrees: Int, name: String,
    ): Uri? {
        val uri = insert(context, "$name.dng", "image/x-adobe-dng") ?: return null
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation(orientationDegrees))
            context.contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, image) }
        }
        publish(context, uri)
        return uri
    }

    /** DNG from a frame we already copied out of the camera (RAW burst). */
    fun saveDngFrame(
        context: Context, characteristics: CameraCharacteristics, result: TotalCaptureResult,
        frame: RawFrame, orientationDegrees: Int, name: String,
    ): Uri? {
        val uri = insert(context, "$name.dng", "image/x-adobe-dng") ?: return null
        val buf = java.nio.ByteBuffer.allocateDirect(frame.w * frame.h * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(frame.data)
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation(orientationDegrees))
            context.contentResolver.openOutputStream(uri)?.use { dng.writeByteBuffer(it, android.util.Size(frame.w, frame.h), buf, 0) }
        }
        publish(context, uri)
        return uri
    }

    /**
     * Saves a computed image (time modes, double exposure): look applied, JPEG at quality 95,
     * EXIF records what made it. The bitmap must already be upright.
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, look: Look, name: String, description: String, lookApplied: Boolean = false): Uri? {
        val src = bitmap
        if (!look.isNeutral && !lookApplied && bitmap.isMutable) applyLookInPlace(bitmap, look)
        val bytes = ByteArrayOutputStream().use { bos -> src.compress(Bitmap.CompressFormat.JPEG, 96, bos); bos.toByteArray() }
        val uri = insert(context, "$name.jpg", "image/jpeg") ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Obscura · ${look.name}")
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                exif.saveAttributes()
            }
        }
        publish(context, uri)
        return uri
    }

    private fun applyLook(jpeg: ByteArray, look: Look): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inMutable = true }) ?: return jpeg
        applyLookInPlace(src, look)
        return ByteArrayOutputStream().use { bos ->
            src.compress(Bitmap.CompressFormat.JPEG, 96, bos)
            src.recycle()
            bos.toByteArray()
        }
    }

    /** LUT, vignette and grain, row by row, in place: no second full-size bitmap. */
    fun applyLookInPlace(bmp: Bitmap, look: Look) {
        if (look.isNeutral) return
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) row[x] = LookFx.apply(row[x], x, y, w, h, look)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
    )

    private fun copyExif(context: Context, original: ByteArray, target: Uri, look: Look) {
        runCatching {
            val src = ExifInterface(ByteArrayInputStream(original))
            context.contentResolver.openFileDescriptor(target, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                EXIF_TAGS.forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Obscura · ${look.name}")
                dst.saveAttributes()
            }
        }
    }

    private fun insert(context: Context, displayName: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun publish(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun exifOrientation(degrees: Int) = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
