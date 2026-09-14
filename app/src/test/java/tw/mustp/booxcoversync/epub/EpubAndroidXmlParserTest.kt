package tw.mustp.booxcoversync.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression coverage for the Android 11 XmlPullParser path used on BOOX. */
@RunWith(RobolectricTestRunner::class)
class EpubAndroidXmlParserTest {

    @Before
    fun forceAndroidParserPath() {
        System.setProperty("booxcoversync.force.android.xml.pull", "true")
    }

    @After
    fun restoreParserPath() {
        System.clearProperty("booxcoversync.force.android.xml.pull")
    }

    @Test
    fun `Android parser reads standard container and OPF fixture`() {
        val epub = zip(
            container = resource("/epub/container.xml"),
            opf = resource("/epub/package.opf"),
            cover = byteArrayOf(7, 8, 9),
        )

        val result = EpubCoverExtractor.extract(ByteArrayInputStream(epub))

        assertEquals("Android parser regression", result.title)
        assertEquals("image/jpeg", result.mimeType)
        assertArrayEquals(byteArrayOf(7, 8, 9), result.bytes)
    }

    @Test
    fun `Android parser rejects DOCTYPE in container`() {
        val container = """
            <?xml version="1.0"?>
            <!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///data/private-book">]>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles>
            </container>
        """.trimIndent().toByteArray()

        assertThrows(EpubExtractionException.InvalidMetadata::class.java) {
            EpubCoverExtractor.extract(
                ByteArrayInputStream(
                    zip(container, resource("/epub/package.opf"), byteArrayOf(1)),
                ),
            )
        }
    }

    @Test
    fun `Android parser rejects ENTITY in OPF`() {
        val opf = """
            <?xml version="1.0"?>
            <!DOCTYPE package [<!ENTITY xxe SYSTEM "file:///data/private-book">]>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>&xxe;</dc:title></metadata>
              <manifest><item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/></manifest>
            </package>
        """.trimIndent().toByteArray()

        assertThrows(EpubExtractionException.InvalidMetadata::class.java) {
            EpubCoverExtractor.extract(
                ByteArrayInputStream(zip(resource("/epub/container.xml"), opf, byteArrayOf(1))),
            )
        }
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test fixture: $path" }
            .use { it.readBytes() }

    private fun zip(container: ByteArray, opf: ByteArray, cover: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(container)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
            zip.write(cover)
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
