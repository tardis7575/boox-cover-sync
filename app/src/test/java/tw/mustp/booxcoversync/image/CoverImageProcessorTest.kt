package tw.mustp.booxcoversync.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverImageProcessorTest {

    @Test
    fun `fit center preserves aspect ratio and pads with white`() {
        val source = solidBitmap(width = 200, height = 100, color = Color.RED)

        val prepared = CoverImageProcessor.prepare(encodePng(source), ScaleMode.FIT_CENTER)

        assertEquals(CoverImageProcessor.TARGET_WIDTH, prepared.width)
        assertEquals(CoverImageProcessor.TARGET_HEIGHT, prepared.height)
        assertEquals(Color.WHITE, prepared.getPixel(0, 0))
        assertEquals(Color.WHITE, prepared.getPixel(0, prepared.height - 1))
        assertEquals(Color.RED, prepared.getPixel(prepared.width / 2, prepared.height / 2))
    }

    @Test
    fun `center crop fills the complete target canvas`() {
        val source = solidBitmap(width = 200, height = 100, color = Color.BLUE)

        val prepared = CoverImageProcessor.prepare(encodePng(source), ScaleMode.CENTER_CROP)

        assertEquals(Color.BLUE, prepared.getPixel(0, 0))
        assertEquals(Color.BLUE, prepared.getPixel(prepared.width - 1, prepared.height - 1))
        assertTrue(prepared.allPixels { it == Color.BLUE })
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(color)
        }

    private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }

    private fun Bitmap.allPixels(predicate: (Int) -> Boolean): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.all(predicate)
    }
}
