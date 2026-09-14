package tw.mustp.booxcoversync.epub

/**
 * The cover bytes selected from an EPUB.
 *
 * The bytes are the original image bytes from the archive. Image decoding and
 * scaling are deliberately kept in the image package so that archive parsing
 * never allocates a bitmap for an untrusted input.
 */
data class ExtractedCover(
    val title: String?,
    val mimeType: String,
    val bytes: ByteArray,
)
