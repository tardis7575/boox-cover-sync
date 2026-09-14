package tw.mustp.booxcoversync.autosync

import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Maps the private NeoReader FileProvider URI to the public external-storage
 * path used by the BOOX device. The mapping is deliberately fail-closed.
 */
object BooxPrivateUriMapper {
    const val FILE_PROVIDER_AUTHORITY = "com.onyx.kreader.onyx.fileprovider"
    private const val EXTERNAL_ROOT_SEGMENT = "external"
    private const val EPUB_EXTENSION = ".epub"

    /** Returns a canonical EPUB file only when [uri] is a safe BOOX URI. */
    fun mapToFile(
        uri: Uri,
        externalStorageRoot: File = Environment.getExternalStorageDirectory(),
    ): File? {
        if (uri.scheme != "content" || uri.authority != FILE_PROVIDER_AUTHORITY) {
            return null
        }

        val decodedPath = uri.encodedPath?.let(Uri::decode) ?: return null
        if (decodedPath.isEmpty() || !decodedPath.startsWith('/')) return null

        val segments = decodedPath.split('/').drop(1)
        if (segments.isEmpty() || segments.first() != EXTERNAL_ROOT_SEGMENT) return null
        if (segments.any { segment ->
                segment.isEmpty() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.contains('\u0000') ||
                    segment.contains('\\')
            }
        ) {
            return null
        }

        val relativeSegments = segments.drop(1)
        val fileName = relativeSegments.lastOrNull() ?: return null
        if (!fileName.lowercase(Locale.ROOT).endsWith(EPUB_EXTENSION)) return null

        return try {
            val root = externalStorageRoot.canonicalFile
            val candidate = relativeSegments.fold(root) { parent, segment ->
                File(parent, segment)
            }.canonicalFile
            if (!candidate.toPath().startsWith(root.toPath())) {
                null
            } else {
                candidate
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Convenience form for callers that need a URI-shaped input. */
    fun mapToUri(
        uri: Uri,
        externalStorageRoot: File = Environment.getExternalStorageDirectory(),
    ): Uri? = mapToFile(uri, externalStorageRoot)?.let(Uri::fromFile)
}
