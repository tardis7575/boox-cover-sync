package tw.mustp.booxcoversync.autosync

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BooxPrivateUriMapperTest {
    private val externalRoot = File("build/boox-private-uri-root").canonicalFile

    @Test
    fun `private FileProvider EPUB is mapped to a decoded file URI`() {
        val uri = Uri.parse(
            "content://com.onyx.kreader.onyx.fileprovider/external/Books/My%20Book.EPUB",
        )

        val mapped = BooxPrivateUriMapper.mapToUri(uri, externalRoot)

        assertEquals(
            Uri.fromFile(File(externalRoot, "Books${File.separator}My Book.EPUB").canonicalFile),
            mapped,
        )
    }

    @Test
    fun `only the external root is accepted`() {
        assertNull(
            BooxPrivateUriMapper.mapToUri(
                Uri.parse("content://com.onyx.kreader.onyx.fileprovider/books/book.epub"),
                externalRoot,
            ),
        )
        assertNull(
            BooxPrivateUriMapper.mapToUri(
                Uri.parse("content://other.provider/external/books/book.epub"),
                externalRoot,
            ),
        )
    }

    @Test
    fun `encoded traversal is rejected before mapping`() {
        assertNull(
            BooxPrivateUriMapper.mapToUri(
                Uri.parse(
                    "content://com.onyx.kreader.onyx.fileprovider/external/Books/%2E%2E/secret.epub",
                ),
                externalRoot,
            ),
        )
        assertNull(
            BooxPrivateUriMapper.mapToUri(
                Uri.parse(
                    "content://com.onyx.kreader.onyx.fileprovider/external/Books/%2Foutside.epub",
                ),
                externalRoot,
            ),
        )
        assertNull(
            BooxPrivateUriMapper.mapToUri(
                Uri.parse(
                    "content://com.onyx.kreader.onyx.fileprovider/external/Books/%5C..%5Csecret.epub",
                ),
                externalRoot,
            ),
        )
    }

    @Test
    fun `only EPUB extension is accepted case insensitively`() {
        val accepted = BooxPrivateUriMapper.mapToUri(
            Uri.parse(
                "content://com.onyx.kreader.onyx.fileprovider/external/Books/book.EpUb",
            ),
            externalRoot,
        )
        val rejected = BooxPrivateUriMapper.mapToUri(
            Uri.parse(
                "content://com.onyx.kreader.onyx.fileprovider/external/Books/book.pdf",
            ),
            externalRoot,
        )

        assertEquals(
            Uri.fromFile(File(externalRoot, "Books${File.separator}book.EpUb").canonicalFile),
            accepted,
        )
        assertNull(rejected)
    }
}
