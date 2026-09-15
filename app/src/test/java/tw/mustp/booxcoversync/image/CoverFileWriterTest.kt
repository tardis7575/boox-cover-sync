package tw.mustp.booxcoversync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverFileWriterTest {

    @Test
    fun `stable hex key keeps basename for same cover and separates different covers`() {
        val context = RuntimeEnvironment.getApplication()
        val firstKey = "a".repeat(64)
        val secondKey = "b".repeat(64)
        val outputs = mutableListOf<java.io.File>()
        clearGeneratedFiles(context)
        try {
            outputs += CoverFileWriter.writeJpegAtomically(
                context,
                solidBitmap(Color.WHITE),
                stableKey = firstKey,
            )
            outputs += CoverFileWriter.writeJpegAtomically(
                context,
                solidBitmap(Color.BLACK),
                stableKey = secondKey,
            )
            outputs += CoverFileWriter.writeJpegAtomically(
                context,
                solidBitmap(Color.RED),
                stableKey = firstKey,
            )

            assertEquals(outputs[0].name, outputs[2].name)
            assertNotEquals(outputs[0].name, outputs[1].name)
            assertTrue(outputs[0].name.matches(Regex("cover-[0-9a-f]{64}\\.jpg")))
            assertTrue(outputs[1].name.matches(Regex("cover-[0-9a-f]{64}\\.jpg")))
        } finally {
            outputs.forEach { it.delete() }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stable key rejects non hex input`() {
        val context = RuntimeEnvironment.getApplication()
        CoverFileWriter.writeJpegAtomically(
            context,
            solidBitmap(Color.WHITE),
            stableKey = "book title/with-path",
        )
    }

    @Test
    fun `writer alternates two readable paths and keeps file count bounded`() {
        val context = RuntimeEnvironment.getApplication()
        val outputs = mutableListOf<java.io.File>()
        clearGeneratedFiles(context)
        try {
            outputs += CoverFileWriter.writeJpegAtomically(context, solidBitmap(Color.WHITE))
            outputs += CoverFileWriter.writeJpegAtomically(context, solidBitmap(Color.BLACK))
            outputs += CoverFileWriter.writeJpegAtomically(context, solidBitmap(Color.RED))

            assertEquals(listOf("current-a.jpg", "current-b.jpg", "current-a.jpg"), outputs.map { it.name })
            outputs.distinct().forEach { file ->
                assertTrue(file.isFile)
                assertTrue(file.canRead())
                assertTrue(file.length() > 0L)
            }
            val directory = outputs.last().parentFile!!
            val generatedJpegs = directory.listFiles().orEmpty()
                .filter { it.name == "current-a.jpg" || it.name == "current-b.jpg" }
            assertEquals(2, generatedJpegs.size)
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".tmp-") })
        } finally {
            outputs.firstOrNull()?.parentFile?.let { directory ->
                directory.listFiles().orEmpty()
                    .filter {
                        it.name == "current-a.jpg" || it.name == "current-b.jpg" ||
                            it.name == "current.jpg" || it.name.contains(".tmp-")
                    }
                    .forEach { it.delete() }
            }
        }
    }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(color)
        }

    private fun clearGeneratedFiles(context: android.content.Context) {
        listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.filesDir.resolve(Environment.DIRECTORY_PICTURES),
        ).map { it.resolve("BookCover") }
            .forEach { directory ->
                directory.listFiles().orEmpty()
                    .filter {
                        it.name == "current-a.jpg" || it.name == "current-b.jpg" ||
                            it.name == "current.jpg" || it.name.contains(".tmp-")
                    }
                    .forEach { it.delete() }
            }
    }
}
