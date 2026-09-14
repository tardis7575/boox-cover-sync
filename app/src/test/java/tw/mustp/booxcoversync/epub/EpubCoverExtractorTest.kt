package tw.mustp.booxcoversync.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubCoverExtractorTest {

    @Test
    fun `EPUB3 cover-image wins over EPUB2 cover metadata`() {
        val epub = epubBytes(
            containerPath = "OEBPS/content.opf",
            opf = packageXml(
                title = "EPUB 3 title",
                manifest = """
                    <item id="old" href="old.jpg" media-type="image/jpeg"/>
                    <item id="new" href="new.jpg" media-type="image/jpeg" properties="cover-image"/>
                """.trimIndent(),
                metadata = "<meta name=\"cover\" content=\"old\"/>",
            ),
            entries = mapOf(
                "OEBPS/old.jpg" to byteArrayOf(1),
                "OEBPS/new.jpg" to byteArrayOf(2, 3),
            ),
        )

        val result = extract(epub)

        assertEquals("EPUB 3 title", result.title)
        assertEquals("image/jpeg", result.mimeType)
        assertArrayEquals(byteArrayOf(2, 3), result.bytes)
    }

    @Test
    fun `EPUB2 cover metadata is supported`() {
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = packageXml(
                title = "EPUB 2 title",
                manifest = "<item id=\"cover-id\" href=\"images/cover.jpeg\" media-type=\"image/jpeg\"/>",
                metadata = "<meta name=\"cover\" content=\"cover-id\"/>",
            ),
            entries = mapOf("images/cover.jpeg" to byteArrayOf(9, 8, 7)),
        )

        val result = extract(epub)

        assertEquals("EPUB 2 title", result.title)
        assertEquals("image/jpeg", result.mimeType)
        assertArrayEquals(byteArrayOf(9, 8, 7), result.bytes)
    }

    @Test
    fun `path matching normalizes case and percent encoding`() {
        val epub = epubBytes(
            containerPath = "oEbPs/CONTENT.OPF",
            opf = packageXml(
                title = "Encoded title",
                manifest = "<item id=\"cover\" href=\"Images/Cover%20A.JPG\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                metadata = "",
            ),
            entries = mapOf("oebps/images/cover a.jpg" to byteArrayOf(4, 5)),
        )

        val result = extract(epub)

        assertArrayEquals(byteArrayOf(4, 5), result.bytes)
    }

    @Test
    fun `missing cover is typed and does not crash`() {
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = packageXml(
                title = "No cover",
                manifest = "<item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/>",
                metadata = "",
            ),
            entries = mapOf("chapter.xhtml" to "text".toByteArray()),
        )

        assertThrows(EpubExtractionException.NoCoverFound::class.java) { extract(epub) }
    }

    @Test
    fun `zip slip entry is rejected`() {
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = packageXml(
                title = "Unsafe",
                manifest = "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                metadata = "",
            ),
            entries = linkedMapOf(
                "../../escape.jpg" to byteArrayOf(1),
                "cover.jpg" to byteArrayOf(2),
            ),
        )

        assertThrows(EpubExtractionException.UnsafeArchive::class.java) { extract(epub) }
    }

    @Test(timeout = 20_000)
    fun `cover entry over limit is rejected`() {
        val oversized = ByteArray(32 * 1024 * 1024 + 1)
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = packageXml(
                title = "Large cover",
                manifest = "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                metadata = "",
            ),
            entries = mapOf("cover.jpg" to oversized),
        )

        assertThrows(EpubExtractionException.ArchiveLimitExceeded::class.java) { extract(epub) }
    }

    @Test
    fun `Android pull parser accepts the standard BOOX regression EPUB`() {
        val epub = epubBytes(
            containerPath = "OEBPS/content.opf",
            opf = packageXml(
                title = "BOOX regression",
                manifest =
                    "<item id=\"cover\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>",
                metadata = "",
            ),
            entries = mapOf("OEBPS/cover.png" to byteArrayOf(1, 2, 3)),
        )

        val result = withAndroidPullParser { extract(epub) }

        assertEquals("BOOX regression", result.title)
        assertEquals("image/png", result.mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.bytes)
    }

    @Test
    fun `Android pull parser rejects a container DOCTYPE`() {
        val unsafeContainer = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE container [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="content.opf"/></rootfiles>
            </container>
        """.trimIndent()
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = packageXml(
                title = "Unsafe",
                manifest =
                    "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                metadata = "",
            ),
            entries = mapOf("cover.jpg" to byteArrayOf(1)),
            containerXml = unsafeContainer,
        )

        assertThrows(EpubExtractionException.InvalidMetadata::class.java) {
            withAndroidPullParser { extract(epub) }
        }
    }

    @Test
    fun `Android pull parser rejects an OPF entity declaration`() {
        val unsafeOpf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE package [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>&leak;</dc:title></metadata>
              <manifest><item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/></manifest>
            </package>
        """.trimIndent()
        val epub = epubBytes(
            containerPath = "content.opf",
            opf = unsafeOpf,
            entries = mapOf("cover.jpg" to byteArrayOf(1)),
        )

        assertThrows(EpubExtractionException.InvalidMetadata::class.java) {
            withAndroidPullParser { extract(epub) }
        }
    }

    private fun extract(bytes: ByteArray): ExtractedCover =
        EpubCoverExtractor.extract(ByteArrayInputStream(bytes))

    private fun packageXml(title: String, manifest: String, metadata: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>$title</dc:title>
            $metadata
          </metadata>
          <manifest>$manifest</manifest>
        </package>
        """.trimIndent()

    private fun epubBytes(
        containerPath: String,
        opf: String,
        entries: Map<String, ByteArray>,
        containerXml: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="$containerPath" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(containerXml.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(containerPath))
            zip.write(opf.toByteArray())
            zip.closeEntry()

            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun <T> withAndroidPullParser(block: () -> T): T {
        val key = "booxcoversync.force.android.xml.pull"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        return try {
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
