package tw.mustp.booxcoversync.boox

import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BooxScreensaverAdapterTest {
    @Test
    fun `sync sends type 16 then type 17 with the expected extras`() {
        val intents = mutableListOf<Intent>()
        val image = File.createTempFile("cover", ".jpg").apply { deleteOnExit() }
        val adapter = BooxScreensaverAdapter(
            manufacturerProvider = { "ONYX" },
            booxPackageChecker = { true },
            broadcastSender = { intent -> intents += intent },
        )

        val result = adapter.sync(image)

        assertEquals(SyncResult.Success(listOf(16, 17)), result)
        assertEquals(listOf(16, 17), intents.map { it.getIntExtra("type", -1) })
        assertTrue(intents.all { it.action == "onyx.action.SCREENSAVER" })
        assertTrue(intents.all { it.`package` == null })
        assertTrue(intents.all { it.getStringExtra("file") == image.absolutePath })
        assertTrue(intents.all { !it.getBooleanExtra("show_result_hint", true) })
    }

    @Test
    fun `sync can target only the enabled screen`() {
        val sentTypes = mutableListOf<Int>()
        val image = File.createTempFile("cover", ".jpg").apply { deleteOnExit() }
        val adapter = BooxScreensaverAdapter(
            manufacturerProvider = { "BOOX" },
            booxPackageChecker = { true },
            broadcastSender = { intent -> sentTypes += intent.getIntExtra("type", -1) },
        )

        val result = adapter.sync(image, sleepEnabled = false, shutdownEnabled = true)

        assertEquals(SyncResult.Success(listOf(17)), result)
        assertEquals(listOf(17), sentTypes)
    }

    @Test
    fun `sync returns an actionable device error before broadcasting`() {
        var broadcastCount = 0
        val image = File.createTempFile("cover", ".jpg").apply { deleteOnExit() }
        val adapter = BooxScreensaverAdapter(
            manufacturerProvider = { "Samsung" },
            booxPackageChecker = { true },
            broadcastSender = { broadcastCount++ },
        )

        val result = adapter.sync(image)

        assertEquals(
            SyncResult.Failure(
                reason = SyncFailureReason.UNSUPPORTED_DEVICE,
                message = "目前裝置製造商為「Samsung」，僅支援 BOOX／Onyx 裝置。",
            ),
            result,
        )
        assertEquals(0, broadcastCount)
    }

    @Test
    fun `sync reports the first successful target when the second broadcast fails`() {
        val sentTypes = mutableListOf<Int>()
        val image = File.createTempFile("cover", ".jpg").apply { deleteOnExit() }
        val adapter = BooxScreensaverAdapter(
            manufacturerProvider = { "BOOX" },
            booxPackageChecker = { true },
            broadcastSender = { intent ->
                val type = intent.getIntExtra("type", -1)
                sentTypes += type
                if (type == 17) error("receiver unavailable")
            },
        )

        val result = adapter.sync(image)

        assertEquals(listOf(16, 17), sentTypes)
        assertEquals(
            SyncResult.Failure(
                reason = SyncFailureReason.BROADCAST_FAILED,
                message = "同步 BOOX 畫面時發生錯誤；請確認封面檔案仍存在後重試。",
                sentTypes = listOf(16),
                cause = (result as SyncResult.Failure).cause,
            ),
            result,
        )
    }
}
