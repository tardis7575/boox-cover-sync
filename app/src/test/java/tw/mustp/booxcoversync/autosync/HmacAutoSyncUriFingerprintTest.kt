package tw.mustp.booxcoversync.autosync

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HmacAutoSyncUriFingerprintTest {
    @Test
    fun `fingerprint is stable per install and differs for another URI`() {
        val context = testContext()
        val first = HmacAutoSyncUriFingerprint(context)
        val second = HmacAutoSyncUriFingerprint(context)
        val uri = Uri.parse("content://com.onyx.kreader/books/one.epub")
        val otherUri = Uri.parse("content://com.onyx.kreader/books/two.epub")

        assertEquals(first.of(uri), first.of(uri))
        assertEquals(first.of(uri), second.of(uri))
        assertNotEquals(first.of(uri), first.of(otherUri))
    }

    @Test
    fun `stored sync state contains no original URI`() {
        val context = testContext()
        val uri = Uri.parse("content://com.onyx.kreader/private/books/one.epub")
        val fingerprint = HmacAutoSyncUriFingerprint(context).of(uri)
        SharedPreferencesAutoSyncFingerprintStore(context).write(fingerprint)

        val state = context.getSharedPreferences("auto_sync_state", Context.MODE_PRIVATE).all
        assertFalse(state.keys.any { key -> key.contains("one.epub") })
        assertFalse(state.values.any { value -> value.toString().contains("one.epub") })
        assertEquals(fingerprint, state["last_synced_uri_fingerprint_v1"])
    }

    private fun testContext(): Context {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("auto_sync_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return context
    }
}
