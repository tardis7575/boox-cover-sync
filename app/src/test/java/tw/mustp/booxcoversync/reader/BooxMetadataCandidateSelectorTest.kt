package tw.mustp.booxcoversync.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BooxMetadataCandidateSelectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `selects newest EPUB and returns canonical relative path`() {
        val root = temporaryFolder.newFolder("storage")
        val books = temporaryFolder.newFolder("storage", "books")
        val older = File(books, "older.epub").apply { createNewFile() }
        val newest = File(books, "newest.epub").apply { createNewFile() }
        val ignoredPdf = File(books, "ignored.pdf").apply { createNewFile() }

        val result = BooxMetadataCandidateSelector.select(
            rows = listOf(
                BooxMetadataRow(lastAccess = 10L, type = "epub", nativeAbsolutePath = older.path),
                BooxMetadataRow(lastAccess = 30L, type = "EPUB", nativeAbsolutePath = newest.path),
                BooxMetadataRow(lastAccess = 99L, type = "pdf", nativeAbsolutePath = ignoredPdf.path),
            ),
            externalStorageRoot = root,
        )

        requireNotNull(result)
        assertEquals(newest.canonicalFile, result.file)
        assertEquals("books${File.separator}newest.epub", result.relativePath)
        assertEquals(30L, result.lastAccess)
    }

    @Test
    fun `accepts extension type with optional leading dot and path case`() {
        val root = temporaryFolder.newFolder("storage")
        val books = temporaryFolder.newFolder("storage", "Books")
        val epub = File(books, "READING.EPUB").apply { createNewFile() }

        val result = BooxMetadataCandidateSelector.select(
            rows = listOf(
                BooxMetadataRow(lastAccess = 1L, type = ".EpUb", nativeAbsolutePath = epub.path),
            ),
            externalStorageRoot = root,
        )

        requireNotNull(result)
        assertEquals(epub.canonicalFile, result.file)
    }

    @Test
    fun `rejects missing fields and non EPUB rows`() {
        val root = temporaryFolder.newFolder("storage")
        val validPath = temporaryFolder.newFile("storage/book.epub").path

        val rows = listOf(
            BooxMetadataRow(lastAccess = null, type = "epub", nativeAbsolutePath = validPath),
            BooxMetadataRow(lastAccess = 2L, type = null, nativeAbsolutePath = validPath),
            BooxMetadataRow(lastAccess = 3L, type = "", nativeAbsolutePath = validPath),
            BooxMetadataRow(lastAccess = 4L, type = "pdf", nativeAbsolutePath = validPath),
            BooxMetadataRow(lastAccess = 5L, type = "epub", nativeAbsolutePath = null),
            BooxMetadataRow(lastAccess = 6L, type = "epub", nativeAbsolutePath = "   "),
        )

        assertNull(BooxMetadataCandidateSelector.select(rows, root))
    }

    @Test
    fun `rejects NUL relative and traversal paths`() {
        val root = temporaryFolder.newFolder("storage")
        val safe = temporaryFolder.newFile("storage/safe.epub").path

        val rows = listOf(
            BooxMetadataRow(lastAccess = 1L, type = "epub", nativeAbsolutePath = "$safe\u0000suffix"),
            BooxMetadataRow(lastAccess = 2L, type = "epub", nativeAbsolutePath = "safe.epub"),
            BooxMetadataRow(lastAccess = 3L, type = "epub", nativeAbsolutePath = File(root, "dir/../safe.epub").path),
            BooxMetadataRow(lastAccess = 4L, type = "epub", nativeAbsolutePath = File(root, "../outside.epub").path),
        )

        assertNull(BooxMetadataCandidateSelector.select(rows, root))
    }

    @Test
    fun `rejects root itself and sibling prefix outside root`() {
        val parent = temporaryFolder.newFolder("parent")
        val root = File(parent, "books").apply { mkdirs() }
        val sibling = File(parent, "books-other/book.epub").apply {
            parentFile?.mkdirs()
            createNewFile()
        }

        val rootRow = BooxMetadataRow(lastAccess = 2L, type = "epub", nativeAbsolutePath = root.path)
        val siblingRow = BooxMetadataRow(lastAccess = 3L, type = "epub", nativeAbsolutePath = sibling.path)

        assertNull(BooxMetadataCandidateSelector.select(listOf(rootRow, siblingRow), root))
    }

    @Test
    fun `keeps valid older row when newer rows are unsafe`() {
        val root = temporaryFolder.newFolder("storage")
        val valid = temporaryFolder.newFile("storage/valid.epub")
        val outside = temporaryFolder.newFile("outside.epub")

        val result = BooxMetadataCandidateSelector.select(
            rows = listOf(
                BooxMetadataRow(lastAccess = 10L, type = "epub", nativeAbsolutePath = valid.path),
                BooxMetadataRow(lastAccess = 20L, type = "epub", nativeAbsolutePath = outside.path),
            ),
            externalStorageRoot = root,
        )

        requireNotNull(result)
        assertEquals(valid.canonicalFile, result.file)
        assertEquals(10L, result.lastAccess)
    }
}
