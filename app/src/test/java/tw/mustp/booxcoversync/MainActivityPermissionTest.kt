package tw.mustp.booxcoversync

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPermissionTest {
    @Test
    fun `usage access requires manifest permission and allowed app-op`() {
        assertTrue(
            isUsageStatsAccessGranted(
                manifestPermissionGranted = true,
                appOpsMode = AppOpsManager.MODE_ALLOWED,
            ),
        )
    }

    @Test
    fun `usage access is denied when manifest permission is missing`() {
        assertFalse(
            isUsageStatsAccessGranted(
                manifestPermissionGranted = false,
                appOpsMode = AppOpsManager.MODE_ALLOWED,
            ),
        )
    }

    @Test
    fun `usage access is denied when app-op is not allowed`() {
        assertFalse(
            isUsageStatsAccessGranted(
                manifestPermissionGranted = true,
                appOpsMode = AppOpsManager.MODE_IGNORED,
            ),
        )
    }
}
