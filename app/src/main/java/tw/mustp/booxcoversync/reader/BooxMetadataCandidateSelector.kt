package tw.mustp.booxcoversync.reader

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The small, platform-independent part of the BOOX Metadata lookup.
 *
 * A provider row is intentionally reduced to the fields needed for selection.
 * The selector does not log, persist, or otherwise expose any row contents.
 */
data class BooxMetadataRow(
    val lastAccess: Long?,
    val type: String?,
    val nativeAbsolutePath: String?,
)

/** A validated Metadata row selected for the most recent EPUB. */
data class BooxMetadataCandidate(
    val file: File,
    val relativePath: String,
    val lastAccess: Long,
)

/**
 * Selects the newest safe EPUB from BOOX's Metadata rows.
 *
 * This object deliberately has no Android or ContentProvider dependency so the
 * path validation can be tested independently. Callers should pass the shared
 * external-storage root that applies to the current device.
 */
object BooxMetadataCandidateSelector {
    private const val EPUB_EXTENSION = ".epub"

    /**
     * Returns the newest valid EPUB, or null when every row is invalid.
     *
     * The candidate must be an absolute path whose canonical form is strictly
     * below [externalStorageRoot]. The root itself, paths containing traversal
     * segments, and paths with NUL characters are rejected.
     */
    fun select(
        rows: Iterable<BooxMetadataRow>,
        externalStorageRoot: File,
    ): BooxMetadataCandidate? {
        val canonicalRoot = try {
            externalStorageRoot.canonicalFile
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val rootPath = canonicalRoot.toPath()

        return rows.asSequence()
            .mapNotNull { row -> validate(row, canonicalRoot, rootPath) }
            .maxWithOrNull(compareBy<BooxMetadataCandidate> { it.lastAccess })
    }

    private fun validate(
        row: BooxMetadataRow,
        canonicalRoot: File,
        rootPath: java.nio.file.Path,
    ): BooxMetadataCandidate? {
        val lastAccess = row.lastAccess ?: return null
        val type = row.type?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isEpubType(type)) return null

        val rawPath = row.nativeAbsolutePath?.takeIf { it.isNotBlank() } ?: return null
        if (rawPath.indexOf('\u0000') >= 0 || hasTraversalSegment(rawPath)) return null

        val file = File(rawPath)
        if (!file.isAbsolute) return null

        val canonicalFile = try {
            file.canonicalFile
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        val candidatePath = canonicalFile.toPath()
        if (candidatePath == rootPath || !candidatePath.startsWith(rootPath)) return null
        if (!canonicalFile.name.lowercase(Locale.ROOT).endsWith(EPUB_EXTENSION)) return null

        val relativePath = try {
            rootPath.relativize(candidatePath).toString()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (relativePath.isEmpty() || hasTraversalSegment(relativePath)) return null

        return BooxMetadataCandidate(
            file = canonicalFile,
            relativePath = relativePath,
            lastAccess = lastAccess,
        )
    }

    private fun isEpubType(type: String): Boolean {
        val normalized = type.removePrefix(".").lowercase(Locale.ROOT)
        return normalized == EPUB_EXTENSION.removePrefix(".")
    }

    /** Reject both platform separators so traversal cannot hide in input text. */
    private fun hasTraversalSegment(path: String): Boolean {
        return path.split(SEGMENT_SEPARATOR_REGEX).any { segment ->
            segment == "." || segment == ".."
        }
    }

    private val SEGMENT_SEPARATOR_REGEX = Regex("[/\\\\]")
}
