package tw.mustp.booxcoversync.boox

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Small adapter around the BOOX screensaver broadcast.
 *
 * The action and all private extras intentionally live in this class so the
 * rest of the app does not depend on the undocumented BOOX contract.
 */
class BooxScreensaverAdapter private constructor(
    private val context: Context?,
    private val manufacturerProvider: () -> String,
    private val booxPackageChecker: () -> Boolean,
    private val broadcastSender: (Intent) -> Unit,
) {

    /** Creates an adapter that sends broadcasts through [context]. */
    constructor(context: Context) : this(
        context = context,
        manufacturerProvider = { Build.MANUFACTURER },
        booxPackageChecker = { isBooxPackageInstalled(context) },
        broadcastSender = { intent -> context.sendBroadcast(intent) },
    )

    /** Test-only constructor that avoids requiring an Android Context in JVM tests. */
    internal constructor(
        manufacturerProvider: () -> String,
        booxPackageChecker: () -> Boolean,
        broadcastSender: (Intent) -> Unit,
    ) : this(
        context = null,
        manufacturerProvider = manufacturerProvider,
        booxPackageChecker = booxPackageChecker,
        broadcastSender = broadcastSender,
    )

    /**
     * Sends the requested cover to the BOOX sleep and shutdown handlers.
     *
     * The sends are intentionally sequential: BOOX re-processes and caches the
     * shutdown image when type 17 is received, so it must follow type 16.
     */
    fun sync(
        imageFile: File,
        sleepEnabled: Boolean = true,
        shutdownEnabled: Boolean = true,
    ): SyncResult {
        if (!sleepEnabled && !shutdownEnabled) {
            return SyncResult.Failure(
                reason = SyncFailureReason.NO_TARGETS_ENABLED,
                message = "至少啟用一個目標：休眠畫面或關機畫面。",
            )
        }

        val manufacturer = manufacturerProvider().trim()
        if (!isSupportedManufacturer(manufacturer)) {
            return SyncResult.Failure(
                reason = SyncFailureReason.UNSUPPORTED_DEVICE,
                message = "目前裝置製造商為「$manufacturer」，僅支援 BOOX／Onyx 裝置。",
            )
        }

        if (!booxPackageChecker()) {
            return SyncResult.Failure(
                reason = SyncFailureReason.BOOX_PACKAGE_MISSING,
                message = "找不到 BOOX 系統套件 com.onyx；請確認這是在 BOOX／Onyx 裝置上執行。",
            )
        }

        if (!imageFile.isFile || !imageFile.canRead()) {
            return SyncResult.Failure(
                reason = SyncFailureReason.IMAGE_NOT_READABLE,
                message = "封面檔案不存在或不可讀：${imageFile.name}。請重新產生封面後再試。",
            )
        }

        val sentTypes = mutableListOf<Int>()
        val targets = buildList {
            if (sleepEnabled) add(TYPE_SLEEP)
            if (shutdownEnabled) add(TYPE_SHUTDOWN)
        }

        return try {
            targets.forEach { type ->
                broadcastSender(createIntent(imageFile, type))
                sentTypes += type
            }
            SyncResult.Success(sentTypes = sentTypes.toList())
        } catch (securityException: SecurityException) {
            SyncResult.Failure(
                reason = SyncFailureReason.BROADCAST_REJECTED,
                message = "BOOX 廣播被系統拒絕；請確認 BOOX 系統服務可用，並重新開啟 App。",
                sentTypes = sentTypes.toList(),
                cause = securityException,
            )
        } catch (runtimeException: RuntimeException) {
            SyncResult.Failure(
                reason = SyncFailureReason.BROADCAST_FAILED,
                message = "同步 BOOX 畫面時發生錯誤；請確認封面檔案仍存在後重試。",
                sentTypes = sentTypes.toList(),
                cause = runtimeException,
            )
        }
    }

    private fun createIntent(imageFile: File, type: Int): Intent = Intent(ACTION_SCREENSAVER).apply {
        putExtra(EXTRA_TYPE, type)
        putExtra(EXTRA_FILE, imageFile.absolutePath)
        putExtra(EXTRA_SHOW_RESULT_HINT, false)
    }

    companion object {
        private const val ACTION_SCREENSAVER = "onyx.action.SCREENSAVER"
        private const val BOOX_PACKAGE = "com.onyx"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_SHOW_RESULT_HINT = "show_result_hint"

        internal const val TYPE_SLEEP = 16
        internal const val TYPE_SHUTDOWN = 17

        private fun isSupportedManufacturer(manufacturer: String): Boolean {
            val normalized = manufacturer.lowercase(Locale.ROOT)
            return normalized == "boox" || normalized == "onyx" ||
                normalized.contains("boox") || normalized.contains("onyx")
        }

        private fun isBooxPackageInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getApplicationInfo(BOOX_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

enum class SyncFailureReason {
    UNSUPPORTED_DEVICE,
    BOOX_PACKAGE_MISSING,
    IMAGE_NOT_READABLE,
    NO_TARGETS_ENABLED,
    BROADCAST_REJECTED,
    BROADCAST_FAILED,
}

sealed class SyncResult {
    data class Success(val sentTypes: List<Int>) : SyncResult()

    data class Failure(
        val reason: SyncFailureReason,
        val message: String,
        val sentTypes: List<Int> = emptyList(),
        val cause: Throwable? = null,
    ) : SyncResult()
}
