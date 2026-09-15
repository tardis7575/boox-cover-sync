package tw.mustp.booxcoversync.reader

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import java.io.File
import java.io.IOException

/**
 * Locates the most recently accessed safe EPUB from BOOX's private Metadata
 * provider. The provider is not a public Android API, so every failure is
 * intentionally reduced to [NeoReaderLocationResult.NotFound].
 */
class BooxMetadataProviderLocator(
    private val query: (uri: Uri, projection: Array<String>, sortOrder: String?) -> Cursor?,
    private val externalStorageRoot: File = Environment.getExternalStorageDirectory(),
) : NeoReaderLocator {

    constructor(
        context: Context,
        externalStorageRoot: File = Environment.getExternalStorageDirectory(),
    ) : this(
        query = { uri, projection, sortOrder ->
            context.contentResolver.query(uri, projection, null, null, sortOrder)
        },
        externalStorageRoot = externalStorageRoot,
    )

    override fun locate(): NeoReaderLocationResult {
        return try {
            val root = externalStorageRoot.canonicalFile
            val cursor = query(PROVIDER_URI, PROJECTION, SORT_ORDER) ?: return unavailable()
            try {
                if (!hasRequiredColumns(cursor)) return unavailable()

                val lastAccessIndex = cursor.getColumnIndex(COLUMN_LAST_ACCESS)
                val typeIndex = cursor.getColumnIndex(COLUMN_TYPE)
                val pathIndex = cursor.getColumnIndex(COLUMN_NATIVE_ABSOLUTE_PATH)
                val rows = ArrayList<BooxMetadataRow>()

                while (cursor.moveToNext()) {
                    rows += BooxMetadataRow(
                        lastAccess = readTimestamp(cursor, lastAccessIndex),
                        type = cursor.getString(typeIndex),
                        nativeAbsolutePath = cursor.getString(pathIndex),
                    )
                }

                val candidate = BooxMetadataCandidateSelector.select(rows, root)
                    ?: return unavailable()
                val contentUri = privateFileProviderUri(candidate.relativePath) ?: return unavailable()
                NeoReaderLocationResult.Found(
                    NeoReaderLocation(
                        action = ACTION_VIEW,
                        contentUri = contentUri,
                        packageName = NEO_READER_PACKAGE,
                        activityName = null,
                    ),
                )
            } finally {
                cursor.close()
            }
        } catch (_: SecurityException) {
            unavailable()
        } catch (_: IllegalArgumentException) {
            unavailable()
        } catch (_: IOException) {
            unavailable()
        } catch (_: RuntimeException) {
            unavailable()
        }
    }

    private fun hasRequiredColumns(cursor: Cursor): Boolean =
        cursor.getColumnIndex(COLUMN_LAST_ACCESS) >= 0 &&
            cursor.getColumnIndex(COLUMN_TYPE) >= 0 &&
            cursor.getColumnIndex(COLUMN_NATIVE_ABSOLUTE_PATH) >= 0

    private fun readTimestamp(cursor: Cursor, index: Int): Long? {
        if (cursor.isNull(index)) return null
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toLong()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)?.trim()?.toLongOrNull()
            else -> null
        }
    }

    private fun privateFileProviderUri(relative: String): Uri? {
        if (relative.isBlank()) return null

        val segments = relative
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null

        return Uri.Builder()
            .scheme("content")
            .authority(BOOX_FILE_PROVIDER_AUTHORITY)
            .appendPath(EXTERNAL_ROOT_SEGMENT)
            .apply { segments.forEach(::appendPath) }
            .build()
    }

    private fun unavailable(): NeoReaderLocationResult.NotFound =
        NeoReaderLocationResult.NotFound(NO_RESULT_MESSAGE)

    companion object {
        const val BOOX_METADATA_AUTHORITY = "com.onyx.content.database.ContentProvider"
        const val BOOX_METADATA_PATH = "Metadata"
        const val BOOX_FILE_PROVIDER_AUTHORITY = "com.onyx.kreader.onyx.fileprovider"
        const val NEO_READER_PACKAGE = "com.onyx.kreader"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val COLUMN_LAST_ACCESS = "lastAccess"
        const val COLUMN_TYPE = "type"
        const val COLUMN_NATIVE_ABSOLUTE_PATH = "nativeAbsolutePath"

        private const val EXTERNAL_ROOT_SEGMENT = "external"
        private const val NO_RESULT_MESSAGE =
            "BOOX Metadata provider unavailable or no safe EPUB candidate."
        private val PROJECTION = arrayOf(
            COLUMN_LAST_ACCESS,
            COLUMN_TYPE,
            COLUMN_NATIVE_ABSOLUTE_PATH,
        )
        private const val SORT_ORDER = "$COLUMN_LAST_ACCESS DESC"
        private val PROVIDER_URI = Uri.parse(
            "content://$BOOX_METADATA_AUTHORITY/$BOOX_METADATA_PATH",
        )

        internal fun providerUriForTest(): Uri = PROVIDER_URI

        internal fun projectionForTest(): Array<String> = PROJECTION.copyOf()

        internal fun sortOrderForTest(): String = SORT_ORDER
    }
}

/**
 * Event-driven observer for BOOX Metadata changes. It never polls. The
 * observer coalesces callbacks already queued on [handler] and removes the
 * queued callback when stopped, so screen-off and toggle-off can cancel work.
 */
class BooxMetadataContentObserver(
    private val handler: Handler,
    private val register: (uri: Uri, notifyForDescendants: Boolean, observer: ContentObserver) -> Unit,
    private val unregister: (observer: ContentObserver) -> Unit,
    private val onProviderChanged: () -> Unit,
    private val providerUri: Uri = BooxMetadataProviderLocator.providerUriForTest(),
) : ContentObserver(handler) {

    constructor(
        context: Context,
        handler: Handler,
        onProviderChanged: () -> Unit,
        providerUri: Uri = BooxMetadataProviderLocator.providerUriForTest(),
    ) : this(
        handler = handler,
        register = { uri, descendants, observer ->
            context.contentResolver.registerContentObserver(uri, descendants, observer)
        },
        unregister = { observer -> context.contentResolver.unregisterContentObserver(observer) },
        onProviderChanged = onProviderChanged,
        providerUri = providerUri,
    )

    private val stateLock = Any()
    private var started = false
    private var pendingDispatch: Runnable? = null

    /** Returns false when the private provider rejects registration. */
    fun start(): Boolean {
        synchronized(stateLock) {
            if (started) return true
            return try {
                register(providerUri, true, this)
                started = true
                true
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    /** Stops observation and cancels any callback that has not run yet. */
    fun stop() {
        synchronized(stateLock) {
            if (!started) return
            started = false
            pendingDispatch?.let(handler::removeCallbacks)
            pendingDispatch = null
            try {
                unregister(this)
            } catch (_: SecurityException) {
                // The observer is already marked stopped; fail closed.
            } catch (_: RuntimeException) {
                // The observer is already marked stopped; fail closed.
            }
        }
    }

    fun onScreenOff() = stop()

    fun onAutoSyncDisabled() = stop()

    fun isStarted(): Boolean = synchronized(stateLock) { started }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        synchronized(stateLock) {
            if (!started || !isRelevantUri(uri)) return
            if (pendingDispatch != null) return

            val callback = Runnable {
                val shouldDispatch = synchronized(stateLock) {
                    pendingDispatch = null
                    started
                }
                if (shouldDispatch) onProviderChanged()
            }
            pendingDispatch = callback
            try {
                handler.post(callback)
            } catch (_: RuntimeException) {
                pendingDispatch = null
            }
        }
    }

    private fun isRelevantUri(uri: Uri?): Boolean {
        if (uri == null) return true
        if (uri == providerUri) return true
        return uri.scheme == providerUri.scheme &&
            uri.authority == providerUri.authority &&
            uri.path?.startsWith("${providerUri.path}/") == true
    }
}
