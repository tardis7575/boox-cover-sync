package tw.mustp.booxcoversync.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DumpsysNeoReaderLocatorTest {
    @Test
    fun `locator does not execute dumpsys without DUMP permission`() {
        var executed = false
        val locator = DumpsysNeoReaderLocator(
            hasDumpPermission = { false },
            runner = object : DumpsysRunner {
                override fun runActivityRecents(): DumpsysCommandResult {
                    executed = true
                    return DumpsysCommandResult(0, "", "")
                }
            },
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.PermissionDenied)
        assertTrue((result as NeoReaderLocationResult.PermissionDenied).message.contains("android.permission.DUMP"))
        assertTrue(!executed)
    }

    @Test
    fun `locator does not execute dumpsys without Usage Stats access`() {
        var executed = false
        val locator = DumpsysNeoReaderLocator(
            hasDumpPermission = { true },
            runner = object : DumpsysRunner {
                override fun runActivityRecents(): DumpsysCommandResult {
                    executed = true
                    return DumpsysCommandResult(0, "", "")
                }
            },
            hasUsageStatsAccess = { false },
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.UsageStatsPermissionDenied)
        assertTrue(
            (result as NeoReaderLocationResult.UsageStatsPermissionDenied)
                .message.contains("android.permission.PACKAGE_USAGE_STATS"),
        )
        assertTrue(!executed)
    }

    @Test
    fun `locator parses a successful controlled dumpsys result`() {
        val output =
            "intent={act=android.intent.action.VIEW dat=content://com.onyx.kreader.onyx.fileprovider/external/book.epub " +
                "cmp=com.onyx.kreader/.ui.ReaderTab1Activity}"
        val locator = DumpsysNeoReaderLocator(
            hasDumpPermission = { true },
            runner = object : DumpsysRunner {
                override fun runActivityRecents(): DumpsysCommandResult =
                    DumpsysCommandResult(exitCode = 0, stdout = output, stderr = "")
            },
            hasUsageStatsAccess = { true },
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.Found)
        assertEquals(
            "content://com.onyx.kreader.onyx.fileprovider/external/book.epub",
            (result as NeoReaderLocationResult.Found).location.contentUri.toString(),
        )
    }

    @Test
    fun `locator exposes command failure details`() {
        val locator = DumpsysNeoReaderLocator(
            hasDumpPermission = { true },
            runner = object : DumpsysRunner {
                override fun runActivityRecents(): DumpsysCommandResult =
                    DumpsysCommandResult(exitCode = 1, stdout = "", stderr = "Permission denied")
            },
            hasUsageStatsAccess = { true },
        )

        val result = locator.locate()

        assertTrue(result is NeoReaderLocationResult.CommandFailed)
        assertEquals("Permission denied", (result as NeoReaderLocationResult.CommandFailed).stderr)
    }
}
