package tw.mustp.booxcoversync.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

enum class ScaleMode {
    FIT_CENTER,
    CENTER_CROP,
}

/** Converts an untrusted image into the BOOX Page's 1264x1680 cover canvas. */
object CoverImageProcessor {

    const val TARGET_WIDTH = 1_264
    const val TARGET_HEIGHT = 1_680

    private const val MAX_INPUT_BYTES = 32L * 1024L * 1024L
    private const val MAX_SOURCE_PIXELS = 64L * 1024L * 1024L

    @JvmStatic
    @Throws(ImageProcessingException::class)
    fun prepare(bytes: ByteArray, mode: ScaleMode = ScaleMode.FIT_CENTER): Bitmap {
        if (bytes.isEmpty()) {
            throw ImageProcessingException.InvalidImage("Cover image is empty")
        }
        if (bytes.size.toLong() > MAX_INPUT_BYTES) {
            throw ImageProcessingException.ImageTooLarge(
                "Cover image exceeds the safe input limit",
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw ImageProcessingException.InvalidImage("Cover image is not a supported bitmap")
        }
        if (sourceWidth.toLong() * sourceHeight.toLong() > MAX_SOURCE_PIXELS) {
            throw ImageProcessingException.ImageTooLarge(
                "Cover image dimensions exceed the safe pixel limit",
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sourceWidth, sourceHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inDither = true
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (error: OutOfMemoryError) {
            throw ImageProcessingException.ImageTooLarge(
                "Cover image could not be decoded within memory limits",
                error,
            )
        }
            ?: throw ImageProcessingException.InvalidImage("Cover image could not be decoded")

        val output = try {
            Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            decoded.recycle()
            throw ImageProcessingException.ImageTooLarge(
                "Unable to allocate the output cover canvas",
                error,
            )
        }

        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val scale = when (mode) {
            ScaleMode.FIT_CENTER -> minOf(
                TARGET_WIDTH.toFloat() / decoded.width,
                TARGET_HEIGHT.toFloat() / decoded.height,
            )
            ScaleMode.CENTER_CROP -> maxOf(
                TARGET_WIDTH.toFloat() / decoded.width,
                TARGET_HEIGHT.toFloat() / decoded.height,
            )
        }
        val drawWidth = decoded.width * scale
        val drawHeight = decoded.height * scale
        val left = (TARGET_WIDTH - drawWidth) / 2f
        val top = (TARGET_HEIGHT - drawHeight) / 2f
        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(decoded, null, destination, paint)
        decoded.recycle()
        return output
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            width / (sample * 2) >= TARGET_WIDTH &&
            height / (sample * 2) >= TARGET_HEIGHT
        ) {
            sample *= 2
        }
        return sample
    }
}
