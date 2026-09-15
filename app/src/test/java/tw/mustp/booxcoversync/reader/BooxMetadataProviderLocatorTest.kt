package tw.mustp.booxcoversync.reader

import android.database.MatrixCursor
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class BooxMetadataProviderLocatorTest {
    @Test
    fun `locator queries Metadata with the narrow projection and selects newest safe epub`() {
        val root = Files.createTempDirectory("boox-metadata-root").toFile()
        val older = File(root, "older.epub").apply { writeText("older") }
        val newer = File(root, "newer.epub").apply { writeText("newer") }
        var queriedUri: Uri? = null
        var queriedProjection: Array<String>? = null
        var queriedSort: String? = null

        val locator = BooxMetadataProviderLocator(
            query = { uri, projection, sortOrder ->
                queriedUri = uri
                queriedProjection = projection
                queriedSort = sortOrder
                MatrixCursor(BooxMetadataProviderLocator.projectionForTest()).apply {
                    addRow(arrayOf(100L, "epub", older.absolutePath))
                    addRow(arrayOf(200L, "EPUB", newer.absolutePath))
                }
            },
            externalStorageRoot = root,
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.Found)
        assertEquals(
            "content://com.onyx.kreader.onyx.fileprovider/external/newer.epub",
            (result as NeoReaderLocationResult.Found).location.contentUri.toString(),
        )
        assertEquals(
            "content://com.onyx.content.database.ContentProvider/Metadata",
            queriedUri.toString(),
        )
        assertEquals(
            listOf("lastAccess", "type", "nativeAbsolutePath"),
            queriedProjection?.toList(),
        )
        assertEquals("lastAccess DESC", queriedSort)
    }

    @Test
    fun `locator rejects unsafe paths and non epub rows without leaking details`() {
        val root = Files.createTempDirectory("boox-metadata-root").toFile()
        val safePdf = File(root, "book.pdf").apply { writeText("pdf") }
        val outside = Files.createTempDirectory("boox-metadata-outside").toFile()
            .resolve("outside.epub")
            .apply { writeText("outside") }
        val locator = BooxMetadataProviderLocator(
            query = { _, _, _ ->
                MatrixCursor(BooxMetadataProviderLocator.projectionForTest()).apply {
                    addRow(arrayOf(300L, "pdf", safePdf.absolutePath))
                    addRow(arrayOf(400L, "epub", outside.absolutePath))
                }
            },
            externalStorageRoot = root,
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.NotFound)
        val message = (result as NeoReaderLocationResult.NotFound).message
        assertFalse(message.contains(safePdf.name))
        assertFalse(message.contains(outside.name))
        assertFalse(message.contains(root.absolutePath))
    }

    @Test
    fun `locator fails closed on schema and provider failures`() {
        val schemaFailure = BooxMetadataProviderLocator(
            query = { _, _, _ -> MatrixCursor(arrayOf("unexpected")) },
        )
        val providerFailure = BooxMetadataProviderLocator(
            query = { _, _, _ -> throw SecurityException("private provider") },
        )

        assertTrue(schemaFailure.locate() is NeoReaderLocationResult.NotFound)
        assertTrue(providerFailure.locate() is NeoReaderLocationResult.NotFound)
    }

    @Test
    fun `observer is event driven and stop unregisters and cancels queued callback`() {
        val handler = Handler(Looper.getMainLooper())
        var registeredObserver: ContentObserver? = null
        var registerCount = 0
        var unregisterCount = 0
        var callbackCount = 0
        val observer = BooxMetadataContentObserver(
            handler = handler,
            register = { _, _, registered ->
                registerCount += 1
                registeredObserver = registered
            },
            unregister = {
                unregisterCount += 1
            },
            onProviderChanged = { callbackCount += 1 },
        )

        assertTrue(observer.start())
        assertTrue(observer.start())
        assertEquals(1, registerCount)
        registeredObserver!!.onChange(false, null)
        observer.onScreenOff()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(0, callbackCount)
        assertEquals(1, unregisterCount)
        assertFalse(observer.isStarted())
    }

    @Test
    fun `observer coalesces provider events and restarts after stop`() {
        val handler = Handler(Looper.getMainLooper())
        var registeredObserver: ContentObserver? = null
        var callbackCount = 0
        var unregisterCount = 0
        val observer = BooxMetadataContentObserver(
            handler = handler,
            register = { _, _, registered -> registeredObserver = registered },
            unregister = { unregisterCount += 1 },
            onProviderChanged = { callbackCount += 1 },
        )
        val providerUri = Uri.parse(
            "content://com.onyx.content.database.ContentProvider/Metadata",
        )

        assertTrue(observer.start())
        registeredObserver!!.onChange(false, providerUri)
        registeredObserver!!.onChange(false, providerUri)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(1, callbackCount)

        observer.stop()
        assertEquals(1, unregisterCount)
        assertTrue(observer.start())
        registeredObserver!!.onChange(false, Uri.parse("content://other/Metadata"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(1, callbackCount)
    }
}
