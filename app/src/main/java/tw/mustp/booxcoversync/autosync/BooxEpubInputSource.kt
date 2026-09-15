package tw.mustp.booxcoversync.autosync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Opens the EPUB input without leaking or persisting its URI.
 *
 * A content URI with a read grant stays on the normal ContentResolver path.
 * BOOX's private FileProvider URI is mapped to external storage only when no
 * read grant is present.
 */
class BooxEpubInputSource(
    private val openContent: (Uri) -> InputStream?,
    private val hasReadPermission: (Uri) -> Boolean,
    private val externalStorageRoot: File = Environment.getExternalStorageDirectory(),
) {
    constructor(context: Context) : this(
        openContent = { uri -> context.contentResolver.openInputStream(uri) },
        hasReadPermission = { uri ->
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        },
        externalStorageRoot = Environment.getExternalStorageDirectory(),
    )

    fun open(uri: Uri): InputStream {
        when (uri.scheme) {
            FILE_SCHEME -> return openSharedExternalFile(uri)
            CONTENT_SCHEME -> Unit
            else -> throw IOException("Unsupported EPUB URI")
        }

        val readPermission = try {
            hasReadPermission(uri)
        } catch (_: SecurityException) {
            false
        }
        if (readPermission) {
            return openContent(uri) ?: throw IOException("EPUB content is unavailable")
        }

        BooxPrivateUriMapper.mapToFile(uri, externalStorageRoot)?.let { file ->
            return FileInputStream(file)
        }

        return openContent(uri) ?: throw IOException("EPUB content is unavailable")
    }

    /**
     * Opens a provider-generated file URI only when it resolves to a readable
     * EPUB below the canonical shared external-storage root.
     */
    private fun openSharedExternalFile(uri: Uri): InputStream {
        if (!uri.authority.isNullOrEmpty()) throw IOException("Unsupported EPUB URI")

        val path = uri.path?.takeIf { it.isNotEmpty() } ?: throw IOException("EPUB file is unavailable")
        if (path.indexOf('\u0000') >= 0) throw IOException("Unsupported EPUB URI")

        val root = try {
            externalStorageRoot.canonicalFile
        } catch (_: IOException) {
            throw IOException("EPUB file is unavailable")
        } catch (_: SecurityException) {
            throw IOException("EPUB file is unavailable")
        }
        val file = try {
            File(path).canonicalFile
        } catch (_: IOException) {
            throw IOException("EPUB file is unavailable")
        } catch (_: SecurityException) {
            throw IOException("EPUB file is unavailable")
        }

        if (file == root || !file.toPath().startsWith(root.toPath()) ||
            !file.name.endsWith(EPUB_EXTENSION, ignoreCase = true) ||
            !file.isFile || !file.canRead()
        ) {
            throw IOException("EPUB file is unavailable")
        }
        return FileInputStream(file)
    }

    private companion object {
        const val CONTENT_SCHEME = "content"
        const val FILE_SCHEME = "file"
        const val EPUB_EXTENSION = ".epub"
    }
}
