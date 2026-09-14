package tw.mustp.booxcoversync.reader

import android.app.AppOpsManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Locates the URI currently opened by NeoReader, when the platform permits it. */
interface NeoReaderLocator {
    fun locate(): NeoReaderLocationResult
}

data class NeoReaderLocation(
    val action: String,
    val contentUri: android.net.Uri,
    val packageName: String,
    val activityName: String?,
)

sealed class NeoReaderLocationResult {
    data class Found(val location: NeoReaderLocation) : NeoReaderLocationResult()

    data class PermissionDenied(val message: String) : NeoReaderLocationResult()

    data class UsageStatsPermissionDenied(val message: String) : NeoReaderLocationResult()

    data class NotFound(val message: String) : NeoReaderLocationResult()

    data class CommandFailed(
        val message: String,
        val exitCode: Int,
        val stderr: String,
    ) : NeoReaderLocationResult()
}

data class DumpsysCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface DumpsysRunner {
    fun runActivityRecents(): DumpsysCommandResult
}

/**
 * Runs the single, read-only command used by the locator spike.
 *
 * This is intentionally not used as a long-running dependency by the app. On
 * normal third-party Android installs it is expected to fail without DUMP.
 */
class RuntimeDumpsysRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : DumpsysRunner {
    override fun runActivityRecents(): DumpsysCommandResult {
        return try {
            val process = ProcessBuilder("dumpsys", "activity", "recents").start()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroy()
                return DumpsysCommandResult(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = "",
                    stderr = "dumpsys activity recents timed out after ${timeoutMillis}ms",
                )
            }

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            DumpsysCommandResult(process.exitValue(), stdout, stderr)
        } catch (exception: IOException) {
            DumpsysCommandResult(
                exitCode = PROCESS_START_EXIT_CODE,
                stdout = "",
                stderr = exception.message ?: "Unable to execute dumpsys",
            )
        } catch (exception: SecurityException) {
            DumpsysCommandResult(
                exitCode = PROCESS_START_EXIT_CODE,
                stdout = "",
                stderr = exception.message ?: "dumpsys execution denied",
            )
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        private const val PROCESS_START_EXIT_CODE = -1
        private const val TIMEOUT_EXIT_CODE = -2
    }
}

class DumpsysNeoReaderLocator(
    private val hasDumpPermission: () -> Boolean,
    private val runner: DumpsysRunner,
    private val hasUsageStatsAccess: () -> Boolean = { true },
) : NeoReaderLocator {

    constructor(
        context: Context,
        runner: DumpsysRunner = RuntimeDumpsysRunner(),
    ) : this(
        hasDumpPermission = {
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        },
        runner = runner,
        hasUsageStatsAccess = { UsageStatsAccessChecker.hasAccess(context) },
    )

    override fun locate(): NeoReaderLocationResult {
        if (!hasDumpPermission()) {
            return NeoReaderLocationResult.PermissionDenied(
                message =
                    "目前 App 沒有 android.permission.DUMP，無法讀取系統 recents。" +
                        "此權限通常是 signature 權限；可用 ADB 嘗試授予後重試，" +
                        "否則請使用使用者選擇書庫的降級方案。AccessibilityService 不能單獨取得書名或 URI。",
            )
        }

        if (!hasUsageStatsAccess()) {
            return NeoReaderLocationResult.UsageStatsPermissionDenied(
                message =
                    "目前 App 沒有 Usage Stats 存取權（android.permission.PACKAGE_USAGE_STATS / " +
                        "GET_USAGE_STATS app-op），無法讀取系統 recents。請到「設定 > 特殊應用程式存取權 > " +
                        "使用狀況存取權」允許本 App 後重試；若裝置政策不允許，請使用使用者選擇書庫的降級方案。",
            )
        }

        val command = try {
            runner.runActivityRecents()
        } catch (exception: RuntimeException) {
            return NeoReaderLocationResult.CommandFailed(
                message = "執行 dumpsys activity recents 失敗。",
                exitCode = -1,
                stderr = exception.message.orEmpty(),
            )
        }

        if (command.exitCode != 0) {
            return NeoReaderLocationResult.CommandFailed(
                message = "dumpsys activity recents 回傳錯誤，請確認 DUMP 權限與裝置狀態。",
                exitCode = command.exitCode,
                stderr = command.stderr,
            )
        }

        val location = NeoReaderRecentsParser.parse(command.stdout)
            ?: return NeoReaderLocationResult.NotFound(
                message = "recents 中找不到 NeoReader 的 ACTION_VIEW content URI；請先用 NeoReader 開啟 EPUB。",
            )

        return NeoReaderLocationResult.Found(location)
    }
}

private object UsageStatsAccessChecker {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false

        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        }
    }
}
