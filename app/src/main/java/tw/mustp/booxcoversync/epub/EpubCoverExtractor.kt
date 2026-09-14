package tw.mustp.booxcoversync.epub

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap

/**
 * Extracts the cover declared by an EPUB without extracting the archive.
 *
 * A ContentResolver is used instead of converting the URI to a filesystem
 * path, so this works with Storage Access Framework documents and provider
 * backed URIs. The archive is streamed and only the container, OPF and cover
 * entries are retained in memory.
 */
object EpubCoverExtractor {

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val MAX_ARCHIVE_ENTRIES = 4_096
    private const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
    private const val MAX_COVER_BYTES = 32L * 1024L * 1024L
    private const val MAX_ENTRY_SCAN_BYTES = 128L * 1024L * 1024L
    private const val MAX_ARCHIVE_SCAN_BYTES = 256L * 1024L * 1024L
    private const val MAX_EARLY_IMAGE_ENTRIES = 16
    private const val MAX_EARLY_IMAGE_BYTES = 64L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L
    private const val MIN_RATIO_CHECK_BYTES = 1L * 1024L * 1024L

    /**
     * Extract the EPUB3/EPUB2 declared cover.
     *
     * @throws EpubExtractionException when the URI cannot be read, the archive
     * is malformed/unsafe, or no declared cover can be found.
     */
    @JvmStatic
    @Throws(EpubExtractionException::class)
    fun extract(resolver: ContentResolver, uri: Uri): ExtractedCover {
        val stream = try {
            resolver.openInputStream(uri)
                ?: throw EpubExtractionException.InputUnavailable(
                    "Unable to open EPUB URI: $uri",
                )
        } catch (error: EpubExtractionException) {
            throw error
        } catch (error: IOException) {
            throw EpubExtractionException.InputUnavailable(
                "Unable to open EPUB URI: $uri",
                error,
            )
        } catch (error: RuntimeException) {
            throw EpubExtractionException.InputUnavailable(
                "Unable to open EPUB URI: $uri",
                error,
            )
        }

        return stream.use(::extract)
    }

    /** Stream overload keeps tests independent from a concrete ContentProvider. */
    @JvmStatic
    @Throws(EpubExtractionException::class)
    internal fun extract(input: InputStream): ExtractedCover {
        return try {
            scanArchive(input)
        } catch (error: EpubExtractionException) {
            throw error
        } catch (error: ZipException) {
            throw EpubExtractionException.InvalidArchive(
                "EPUB is not a valid ZIP archive",
                error,
            )
        } catch (error: IOException) {
            throw EpubExtractionException.InvalidArchive(
                "Unable to read EPUB ZIP data",
                error,
            )
        }
    }

    private data class CoverReference(
        val href: String,
        val mimeType: String,
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private data class PackageMetadata(
        val title: String?,
        val cover: CoverReference?,
    )

    private data class EntryReadResult(
        val bytes: ByteArray?,
        val size: Long,
    )

    private fun scanArchive(input: InputStream): ExtractedCover {
        var rootfilePath: String? = null
        var packageMetadata: PackageMetadata? = null
        var coverBytes: ByteArray? = null
        var containerSeen = false
        var opfSeen = false
        var totalBytes = 0L
        var earlyImageBytes = 0L
        var earlyImageEntries = 0
        val seenPaths = HashSet<String>()
        val earlyImages = LinkedHashMap<String, ByteArray>()
        val opfEntries = LinkedHashMap<String, ByteArray>()
        val containerPath = CONTAINER_PATH.lowercase(Locale.ROOT)

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw EpubExtractionException.ArchiveLimitExceeded(
                        "EPUB contains too many entries",
                    )
                }

                val normalizedPath = canonicalPath(entry.name).lowercase(Locale.ROOT)
                if (!seenPaths.add(normalizedPath)) {
                    throw EpubExtractionException.UnsafeArchive(
                        "EPUB contains duplicate archive paths: ${entry.name}",
                    )
                }

                val declaredSize = entry.size
                if (declaredSize > MAX_ENTRY_SCAN_BYTES) {
                    throw EpubExtractionException.ArchiveLimitExceeded(
                        "EPUB entry is too large: ${entry.name}",
                    )
                }

                val expectedOpf = rootfilePath?.lowercase(Locale.ROOT)
                val expectedCover = packageMetadata?.cover?.href?.lowercase(Locale.ROOT)
                val isContainer = normalizedPath == containerPath
                val isOpfCandidate = normalizedPath.endsWith(".opf")
                val isExpectedOpf = expectedOpf == normalizedPath
                val isExpectedCover = expectedCover == normalizedPath
                val retainEarlyImage = !isExpectedCover &&
                    packageMetadata?.cover == null &&
                    isLikelyImagePath(normalizedPath) &&
                    earlyImageEntries < MAX_EARLY_IMAGE_ENTRIES &&
                    earlyImageBytes < MAX_EARLY_IMAGE_BYTES
                val shouldRetain = isContainer || isOpfCandidate || isExpectedOpf ||
                    isExpectedCover || retainEarlyImage

                val maxBytes = when {
                    isContainer || isOpfCandidate || isExpectedOpf -> MAX_METADATA_BYTES
                    isExpectedCover || retainEarlyImage -> MAX_COVER_BYTES
                    else -> MAX_ENTRY_SCAN_BYTES
                }
                val readResult = if (shouldRetain) {
                    readEntry(zip, maxBytes, entry.name, retain = true)
                } else {
                    readEntry(zip, maxBytes, entry.name, retain = false)
                }
                totalBytes += readResult.size
                if (totalBytes > MAX_ARCHIVE_SCAN_BYTES) {
                    throw EpubExtractionException.ArchiveLimitExceeded(
                        "EPUB contains more uncompressed data than allowed",
                    )
                }
                enforceCompressionRatio(entry, readResult.size)

                val bytes = readResult.bytes
                if (isContainer && bytes != null) {
                    containerSeen = true
                    rootfilePath = parseContainer(bytes)
                    opfEntries[rootfilePath!!.lowercase(Locale.ROOT)]?.let { opf ->
                        packageMetadata = parsePackage(opf, rootfilePath!!)
                        opfSeen = true
                        packageMetadata?.cover?.href?.lowercase(Locale.ROOT)?.let { coverPath ->
                            coverBytes = earlyImages[coverPath]
                        }
                    }
                }
                if (isOpfCandidate && bytes != null) {
                    opfEntries[normalizedPath] = bytes
                    if (expectedOpf == normalizedPath || rootfilePath?.lowercase(Locale.ROOT) == normalizedPath) {
                        val selectedPath = rootfilePath ?: canonicalPath(entry.name)
                        packageMetadata = parsePackage(bytes, selectedPath)
                        opfSeen = true
                        packageMetadata?.cover?.href?.lowercase(Locale.ROOT)?.let { coverPath ->
                            coverBytes = earlyImages[coverPath]
                        }
                    }
                }
                if (isExpectedCover && bytes != null) {
                    coverBytes = bytes
                }
                if (retainEarlyImage && bytes != null) {
                    earlyImages[normalizedPath] = bytes
                    earlyImageBytes += bytes.size
                    earlyImageEntries++
                }

                zip.closeEntry()
            }
        }

        if (!containerSeen) {
            throw EpubExtractionException.InvalidMetadata(
                "EPUB is missing META-INF/container.xml",
            )
        }
        val selectedOpfPath = rootfilePath
            ?: throw EpubExtractionException.InvalidMetadata(
                "EPUB container.xml has no usable rootfile",
            )
        if (!opfSeen || packageMetadata == null) {
            throw EpubExtractionException.InvalidMetadata(
                "EPUB container points to a missing OPF: $selectedOpfPath",
            )
        }

        val metadata = packageMetadata!!
        val cover = metadata.cover ?: throw EpubExtractionException.NoCoverFound()
        val image = coverBytes ?: throw EpubExtractionException.CoverEntryNotFound(
            "EPUB cover entry is missing: ${cover.href}",
        )
        return ExtractedCover(metadata.title, cover.mimeType, image)
    }

    private fun parseContainer(bytes: ByteArray): String {
        return if (isAndroidRuntime()) {
            parseContainerWithPullParser(bytes)
        } else {
            parseContainerWithJaxp(bytes)
        }
    }

    /** Android 11 path: XmlPullParser avoids vendor JAXP feature failures. */
    private fun parseContainerWithPullParser(bytes: ByteArray): String {
        val parser = secureParser(bytes, "container.xml")
        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        if (localName(parser) == "rootfile") {
                            val fullPath = attribute(parser, "full-path")
                                ?.trim()
                                ?.takeUnless(String::isBlank)
                                ?: continue
                            return canonicalPath(fullPath)
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (error: EpubExtractionException) {
            throw error
        } catch (error: Exception) {
            throw EpubExtractionException.InvalidMetadata(
                "Unable to parse EPUB container.xml",
                error,
            )
        }
        throw EpubExtractionException.InvalidMetadata(
            "EPUB container.xml has no rootfile full-path",
        )
    }

    private fun parsePackage(bytes: ByteArray, opfPath: String): PackageMetadata {
        return if (isAndroidRuntime()) {
            parsePackageWithPullParser(bytes, opfPath)
        } else {
            parsePackageWithJaxp(bytes, opfPath)
        }
    }

    /** Android 11 path: only metadata tokens are read; entity events fail closed. */
    private fun parsePackageWithPullParser(bytes: ByteArray, opfPath: String): PackageMetadata {
        val parser = secureParser(bytes, "package document")
        val manifestItems = mutableListOf<ManifestItem>()
        var epub2CoverId: String? = null
        var titleDepth: Int? = null
        var titleText: StringBuilder? = null

        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        when (localName(parser)) {
                            "item" -> {
                                val id = attribute(parser, "id")?.trim().orEmpty()
                                val href = attribute(parser, "href")?.trim().orEmpty()
                                if (id.isNotBlank() && href.isNotBlank()) {
                                    manifestItems += ManifestItem(
                                        id = id,
                                        href = resolveHref(opfPath, href),
                                        mediaType = attribute(parser, "media-type")?.trim().orEmpty(),
                                        properties = attribute(parser, "properties")
                                            .orEmpty()
                                            .split(Regex("\\s+"))
                                            .filter(String::isNotBlank)
                                            .map { it.lowercase(Locale.ROOT) }
                                            .toSet(),
                                    )
                                }
                            }
                            "meta" -> {
                                if (attribute(parser, "name")?.equals("cover", ignoreCase = true) == true) {
                                    epub2CoverId = attribute(parser, "content")
                                        ?.trim()
                                        ?.takeUnless(String::isBlank)
                                }
                            }
                            "title" -> {
                                if (titleDepth == null) {
                                    titleDepth = parser.depth
                                    titleText = StringBuilder()
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (titleDepth != null) titleText?.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        if (localName(parser) == "title" && parser.depth == titleDepth) {
                            titleDepth = null
                        }
                    }
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> {
                        throw EpubExtractionException.InvalidMetadata(
                            "EPUB metadata contains a forbidden DOCTYPE/entity declaration",
                        )
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (error: EpubExtractionException) {
            throw error
        } catch (error: Exception) {
            throw EpubExtractionException.InvalidMetadata(
                "Unable to parse EPUB package document",
                error,
            )
        }

        if (manifestItems.isEmpty()) {
            throw EpubExtractionException.InvalidMetadata(
                "EPUB package has no usable manifest items",
            )
        }

        val byId = manifestItems.associateBy { it.id }
        val epub3Cover = manifestItems.firstOrNull { item ->
            "cover-image" in item.properties && isImage(item)
        }

        val selected = epub3Cover
            ?: epub2CoverId?.let { byId[it] }?.takeIf(::isImage)

        val cover = selected?.let {
            CoverReference(
                href = it.href,
                mimeType = normalizedMimeType(it.mediaType, it.href),
            )
        }

        val title = titleText
            ?.toString()
            ?.trim()
            ?.takeUnless(String::isBlank)

        return PackageMetadata(title = title, cover = cover)
    }

    /**
     * JVM/unit-test path. Android's android.jar intentionally provides a stub
     * for android.util.Xml, so local JVM tests use a separately hardened JAXP
     * parser. This branch is never selected on a Dalvik/ART runtime.
     */
    private fun parseContainerWithJaxp(bytes: ByteArray): String {
        val document = parseXmlWithJaxp(bytes, "container.xml")
        val rootfile = elementsByLocalName(document, "rootfile")
            .firstOrNull { attr(it, "full-path")?.isNotBlank() == true }
            ?: throw EpubExtractionException.InvalidMetadata(
                "EPUB container.xml has no rootfile full-path",
            )
        return canonicalPath(attr(rootfile, "full-path")!!)
    }

    private fun parsePackageWithJaxp(bytes: ByteArray, opfPath: String): PackageMetadata {
        val document = parseXmlWithJaxp(bytes, "package document")
        val manifestItems = elementsByLocalName(document, "item")
            .mapNotNull { item ->
                val id = attr(item, "id")?.trim().orEmpty()
                val href = attr(item, "href")?.trim().orEmpty()
                if (id.isBlank() || href.isBlank()) {
                    null
                } else {
                    ManifestItem(
                        id = id,
                        href = resolveHref(opfPath, href),
                        mediaType = attr(item, "media-type")?.trim().orEmpty(),
                        properties = attr(item, "properties")
                            .orEmpty()
                            .split(Regex("\\s+"))
                            .filter(String::isNotBlank)
                            .map { it.lowercase(Locale.ROOT) }
                            .toSet(),
                    )
                }
            }
        val epub2CoverId = elementsByLocalName(document, "meta")
            .firstOrNull { meta ->
                attr(meta, "name")?.equals("cover", ignoreCase = true) == true
            }
            ?.let { attr(it, "content")?.trim() }
            ?.takeUnless(String::isBlank)
        val title = elementsByLocalName(document, "title")
            .asSequence()
            .mapNotNull { it.textContent?.trim()?.takeUnless(String::isBlank) }
            .firstOrNull()
        return packageMetadata(manifestItems, epub2CoverId, title)
    }

    private fun packageMetadata(
        manifestItems: List<ManifestItem>,
        epub2CoverId: String?,
        title: String?,
    ): PackageMetadata {
        if (manifestItems.isEmpty()) {
            throw EpubExtractionException.InvalidMetadata(
                "EPUB package has no usable manifest items",
            )
        }
        val byId = manifestItems.associateBy { it.id }
        val epub3Cover = manifestItems.firstOrNull { item ->
            "cover-image" in item.properties && isImage(item)
        }
        val selected = epub3Cover
            ?: epub2CoverId?.let { byId[it] }?.takeIf(::isImage)
        val cover = selected?.let {
            CoverReference(
                href = it.href,
                mimeType = normalizedMimeType(it.mediaType, it.href),
            )
        }
        return PackageMetadata(title = title, cover = cover)
    }

    private fun isImage(item: ManifestItem): Boolean {
        val mediaType = item.mediaType.lowercase(Locale.ROOT)
        return mediaType.startsWith("image/") || imageExtension(item.href)
    }

    private fun normalizedMimeType(mediaType: String, href: String): String {
        val explicit = mediaType.trim().lowercase(Locale.ROOT)
        if (explicit.startsWith("image/")) return explicit

        return when (imageExtensionName(href)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    private fun imageExtension(href: String): Boolean =
        imageExtensionName(href) in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    private fun imageExtensionName(href: String): String? {
        val path = href.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        return name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .takeUnless(String::isBlank)
    }

    private fun resolveHref(opfPath: String, href: String): String {
        val opfDirectory = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val hrefWithoutFragment = href.substringBefore('#').substringBefore('?')
            .removePrefix("/")
        return canonicalPath(
            if (opfDirectory.isBlank()) hrefWithoutFragment
            else "$opfDirectory/$hrefWithoutFragment",
        )
    }

    private fun readEntry(
        input: InputStream,
        maxBytes: Long,
        entryName: String,
        retain: Boolean,
    ): EntryReadResult {
        val output = if (retain) ByteArrayOutputStream() else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw EpubExtractionException.ArchiveLimitExceeded(
                    "EPUB entry exceeds the safe size limit: $entryName",
                )
            }
            output?.write(buffer, 0, count)
        }
        return EntryReadResult(bytes = output?.toByteArray(), size = total)
    }

    private fun enforceCompressionRatio(entry: ZipEntry, uncompressedSize: Long) {
        val compressedSize = entry.compressedSize
        if (compressedSize > 0L && uncompressedSize >= MIN_RATIO_CHECK_BYTES &&
            uncompressedSize / compressedSize > MAX_COMPRESSION_RATIO
        ) {
            throw EpubExtractionException.ArchiveLimitExceeded(
                "EPUB entry has an unsafe compression ratio: ${entry.name}",
            )
        }
    }

    private fun isLikelyImagePath(path: String): Boolean =
        imageExtensionName(path) in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("booxcoversync.force.android.xml.pull") == "true" ||
            System.getProperty("java.runtime.name")
                ?.contains("Android", ignoreCase = true) == true ||
            runCatching {
                val fingerprint = android.os.Build.FINGERPRINT.orEmpty()
                fingerprint.isNotBlank() && !fingerprint.equals("robolectric", ignoreCase = true)
            }.getOrDefault(false)

    private fun parseXmlWithJaxp(bytes: ByteArray, description: String): Document {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                try {
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                } catch (_: IllegalArgumentException) {
                    // Optional on some JAXP implementations; core features above are required.
                }
                try {
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
                } catch (_: IllegalArgumentException) {
                    // Optional on some JAXP implementations; core features above are required.
                }
            }
            val xmlBytes = bytes.toString(StandardCharsets.UTF_8)
                .removePrefix("\uFEFF")
                .trimStart()
                .toByteArray(StandardCharsets.UTF_8)
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        } catch (error: Exception) {
            throw EpubExtractionException.InvalidMetadata(
                "Unable to parse EPUB $description",
                error,
            )
        }
    }

    private fun elementsByLocalName(document: Document, localName: String): List<Element> {
        val namespaced = document.getElementsByTagNameNS("*", localName)
        if (namespaced.length > 0) {
            return (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }
        }
        val unqualified = document.getElementsByTagName(localName)
        return (0 until unqualified.length).mapNotNull { unqualified.item(it) as? Element }
    }

    private fun attr(element: Element, name: String): String? {
        element.getAttribute(name).takeUnless(String::isBlank)?.let { return it }
        val attributes: NamedNodeMap = element.attributes
        for (index in 0 until attributes.length) {
            val node = attributes.item(index)
            val local = node.localName ?: node.nodeName.substringAfterLast(':')
            if (local.equals(name, ignoreCase = true)) return node.nodeValue
        }
        return null
    }

    private fun secureParser(bytes: ByteArray, description: String): XmlPullParser {
        val normalized = bytes.toString(StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
        val uppercase = normalized.uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in uppercase || "<!ENTITY" in uppercase) {
            throw EpubExtractionException.InvalidMetadata(
                "EPUB $description contains a forbidden DOCTYPE/entity declaration",
            )
        }

        return try {
            Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                try {
                    setFeature(
                        "http://xmlpull.org/v1/doc/features.html#process-docdecl",
                        false,
                    )
                } catch (_: XmlPullParserException) {
                    // Some Android 11 vendor parsers do not expose this
                    // optional feature. The raw DOCTYPE/ENTITY rejection above
                    // remains mandatory and prevents unsafe documents.
                }
                setInput(ByteArrayInputStream(normalized.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
            }
        } catch (error: EpubExtractionException) {
            throw error
        } catch (error: Exception) {
            throw EpubExtractionException.InvalidMetadata(
                "Unable to initialize secure EPUB $description parser",
                error,
            )
        }
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name.substringAfterLast(':').lowercase(Locale.ROOT)

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            val attributeName = parser.getAttributeName(index).substringAfterLast(':')
            if (attributeName.equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    /** Canonical EPUB path; rejects traversal and absolute filesystem paths. */
    private fun canonicalPath(rawPath: String): String {
        val decoded = try {
            // Decode a few layers so an encoded traversal such as
            // %252e%252e cannot evade the Zip Slip check. URLDecoder treats
            // '+' as a space, while EPUB href paths treat it as a literal plus.
            var current = rawPath
            repeat(3) {
                val next = URLDecoder.decode(
                    current.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name(),
                )
                if (next == current) return@repeat
                current = next
            }
            current
        } catch (error: IllegalArgumentException) {
            throw EpubExtractionException.UnsafeArchive(
                "EPUB path has invalid percent encoding: $rawPath",
                error,
            )
        }

        if (decoded.indexOf('\u0000') >= 0 || decoded.startsWith('/') ||
            decoded.matches(Regex("^[A-Za-z]:[\\\\/].*"))
        ) {
            throw EpubExtractionException.UnsafeArchive(
                "EPUB path is absolute or contains NUL: $rawPath",
            )
        }

        val segments = decoded.replace('\\', '/').split('/')
        val normalized = ArrayDeque<String>()
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (normalized.isEmpty()) {
                        throw EpubExtractionException.UnsafeArchive(
                            "EPUB path escapes the archive root: $rawPath",
                        )
                    }
                    normalized.removeLast()
                }
                else -> normalized.addLast(segment)
            }
        }

        if (normalized.isEmpty()) {
            throw EpubExtractionException.UnsafeArchive("EPUB path is empty: $rawPath")
        }
        return normalized.joinToString("/")
    }
}
