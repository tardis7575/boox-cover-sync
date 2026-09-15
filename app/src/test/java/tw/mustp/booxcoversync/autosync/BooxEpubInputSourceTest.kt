package tw.mustp.booxcoversync.autosync

import android.net.Uri
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BooxEpubInputSourceTest {
    private val externalRoot = File("build/boox-input-source-root").canonicalFile

    @After
    fun cleanUp() {
        externalRoot.deleteRecursively()
    }

    @Test
    fun `read grant keeps content URI input path`() {
        val uri = Uri.parse(
            "content://com.onyx.kreader.onyx.fileprovider/external/Books/book.epub",
        )
        var contentCalls = 0
        val source = BooxEpubInputSource(
            openContent = {
                contentCalls += 1
                ByteArrayInputStream("content".toByteArray(StandardCharsets.UTF_8))
            },
            hasReadPermission = { true },
            externalStorageRoot = externalRoot,
        )

        source.open(uri).use { input ->
            assertEquals("content", input.readBytes().toString(StandardCharsets.UTF_8))
        }

        assertEquals(1, contentCalls)
    }

    @Test
    fun `private URI without read grant falls back to external storage file`() {
        val file = File(externalRoot, "Books/book.epub").apply {
            parentFile?.mkdirs()
            writeText("file")
        }
        val uri = Uri.parse(
            "content://com.onyx.kreader.onyx.fileprovider/external/Books/book.epub",
        )
        var contentCalls = 0
        val source = BooxEpubInputSource(
            openContent = {
                contentCalls += 1
                error("content URI must not be opened without a grant")
            },
            hasReadPermission = { false },
            externalStorageRoot = externalRoot,
        )

        source.open(uri).use { input ->
            assertEquals("file", input.readBytes().toString(StandardCharsets.UTF_8))
        }

        assertTrue(file.isFile)
        assertEquals(0, contentCalls)
    }

    @Test
    fun `non private content URI remains on the content resolver path`() {
        val uri = Uri.parse("content://books.provider/books/book.epub")
        var contentCalls = 0
        val source = BooxEpubInputSource(
            openContent = {
                contentCalls += 1
                ByteArrayInputStream("content".toByteArray(StandardCharsets.UTF_8))
            },
            hasReadPermission = { false },
            externalStorageRoot = externalRoot,
        )

        source.open(uri).use { input ->
            assertEquals("content", input.readBytes().toString(StandardCharsets.UTF_8))
        }

        assertEquals(1, contentCalls)
    }

    @Test
    fun `safe shared external file URI opens without content resolver`() {
        val file = File(externalRoot, "Books/provider-book.epub").apply {
            parentFile?.mkdirs()
            writeText("file")
        }
        val source = BooxEpubInputSource(
            openContent = { error("file URI must not use content resolver") },
            hasReadPermission = { error("file URI must not check URI permission") },
            externalStorageRoot = externalRoot,
        )

        source.open(Uri.fromFile(file)).use { input ->
            assertEquals("file", input.readBytes().toString(StandardCharsets.UTF_8))
        }
    }

    @Test(expected = IOException::class)
    fun `file URI outside shared external storage is rejected`() {
        val outside = File(externalRoot.parentFile, "outside.epub").apply { writeText("file") }
        val source = BooxEpubInputSource(
            openContent = { error("outside file must be rejected") },
            hasReadPermission = { error("outside file must not check URI permission") },
            externalStorageRoot = externalRoot,
        )

        source.open(Uri.fromFile(outside))
    }
}
