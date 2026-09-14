package tw.mustp.booxcoversync.epub

import java.io.IOException

/** Typed failures that can be shown as an actionable message by the UI. */
sealed class EpubExtractionException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    class InputUnavailable(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)

    class InvalidArchive(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)

    class UnsafeArchive(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)

    class ArchiveLimitExceeded(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)

    class InvalidMetadata(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)

    class NoCoverFound(message: String = "EPUB does not declare a cover image") :
        EpubExtractionException(message)

    class CoverEntryNotFound(message: String, cause: Throwable? = null) :
        EpubExtractionException(message, cause)
}
