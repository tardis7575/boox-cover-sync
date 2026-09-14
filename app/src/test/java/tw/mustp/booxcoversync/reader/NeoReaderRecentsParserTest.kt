package tw.mustp.booxcoversync.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NeoReaderRecentsParserTest {
    @Test
    fun `parse finds NeoReader view content uri and normalizes relative activity`() {
        val output = """
            Recent tasks:
              * Task{abc #12}
                intent={act=android.intent.action.VIEW dat=content://com.onyx.kreader.onyx.fileprovider/external/Books/A%20Book.epub cmp=com.onyx.kreader/.ui.ReaderTab1Activity flg=0x14000000}
        """.trimIndent()

        val result = NeoReaderRecentsParser.parse(output)

        requireNotNull(result)
        assertEquals("android.intent.action.VIEW", result.action)
        assertEquals(
            "content://com.onyx.kreader.onyx.fileprovider/external/Books/A%20Book.epub",
            result.contentUri.toString(),
        )
        assertEquals("com.onyx.kreader", result.packageName)
        assertEquals("com.onyx.kreader.ui.ReaderTab1Activity", result.activityName)
    }

    @Test
    fun `parse ignores non NeoReader and non VIEW intents`() {
        val output = """
            intent={act=android.intent.action.VIEW dat=content://example.invalid/book.epub cmp=com.example.reader/.ReaderActivity}
            intent={act=android.intent.action.MAIN cmp=com.onyx.kreader/.ui.ReaderTab1Activity}
        """.trimIndent()

        assertNull(NeoReaderRecentsParser.parse(output))
    }

    @Test
    fun `parse accepts fully qualified reader activity`() {
        val output =
            "intent={act=android.intent.action.VIEW dat=content://com.onyx.kreader.onyx.fileprovider/external/book.epub " +
                "cmp=com.onyx.kreader/com.onyx.kreader.ui.ReaderTab1Activity}"

        val result = requireNotNull(NeoReaderRecentsParser.parse(output))

        assertEquals("com.onyx.kreader.ui.ReaderTab1Activity", result.activityName)
    }
}
