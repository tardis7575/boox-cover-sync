package tw.mustp.booxcoversync.reader

import android.net.Uri

/** Pure parser for the intent blocks printed by `dumpsys activity recents`. */
object NeoReaderRecentsParser {
    private const val NEO_READER_PACKAGE = "com.onyx.kreader"
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private val intentBlockRegex = Regex("(?s)intent=\\{.*?\\}")
    // dumpsys prints fields immediately after the opening `{`, or separated by
    // whitespace. Accept both delimiters while keeping the field value bounded
    // by whitespace or the intent's closing brace.
    private val actionRegex = Regex("(?:^|[\\s{])act=([^\\s}]+)")
    private val dataRegex = Regex("(?:^|[\\s{])dat=(content://[^\\s}]+)")
    private val componentRegex = Regex("(?:^|[\\s{])cmp=([^\\s}]+)")

    fun parse(dumpsysOutput: String): NeoReaderLocation? {
        intentBlockRegex.findAll(dumpsysOutput).forEach { match ->
            val block = match.value
            val action = actionRegex.find(block)?.groupValues?.get(1) ?: return@forEach
            if (action != ACTION_VIEW) return@forEach

            val rawUri = dataRegex.find(block)?.groupValues?.get(1) ?: return@forEach
            val uri = Uri.parse(rawUri)
            if (uri.scheme != "content") return@forEach

            val component = componentRegex.find(block)?.groupValues?.get(1) ?: return@forEach
            val separator = component.indexOf('/')
            val packageName = if (separator >= 0) component.substring(0, separator) else component
            if (packageName != NEO_READER_PACKAGE) return@forEach

            val activityName = component
                .substringAfter('/', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.let { activity ->
                    if (activity.startsWith('.')) "$packageName$activity" else activity
                }

            return NeoReaderLocation(
                action = action,
                contentUri = uri,
                packageName = packageName,
                activityName = activityName,
            )
        }
        return null
    }
}
