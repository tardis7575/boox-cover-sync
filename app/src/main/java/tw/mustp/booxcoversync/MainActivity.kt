package tw.mustp.booxcoversync

import android.app.Activity
import android.content.Intent
import android.app.AppOpsManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import tw.mustp.booxcoversync.boox.BooxScreensaverAdapter
import tw.mustp.booxcoversync.epub.EpubCoverExtractor
import tw.mustp.booxcoversync.epub.ExtractedCover
import tw.mustp.booxcoversync.image.CoverFileWriter
import tw.mustp.booxcoversync.image.CoverImageProcessor
import tw.mustp.booxcoversync.image.ScaleMode
import tw.mustp.booxcoversync.boox.SyncResult
import java.text.DateFormat
import java.util.Date

/**
 * Minimal, manual vertical slice for the first MVP milestone.
 *
 * The activity intentionally has no reader polling or ADB dependency. It only
 * performs work after an explicit file selection or sync click.
 */
class MainActivity : Activity() {

    private lateinit var openButton: Button
    private lateinit var syncButton: Button
    private lateinit var sleepSwitch: Switch
    private lateinit var shutdownSwitch: Switch
    private lateinit var coverPreview: ImageView
    private lateinit var selectedFileText: TextView
    private lateinit var bookTitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var storagePermissionStatusText: TextView
    private lateinit var storagePermissionButton: Button
    private lateinit var readerStatusText: TextView
    private lateinit var usageAccessButton: Button
    private lateinit var preferences: android.content.SharedPreferences

    private var selectedUri: Uri? = null
    private var extractedCover: ExtractedCover? = null
    private var preparedBitmap: Bitmap? = null
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openButton = findViewById(R.id.open_epub_button)
        syncButton = findViewById(R.id.sync_button)
        sleepSwitch = findViewById(R.id.sleep_switch)
        shutdownSwitch = findViewById(R.id.shutdown_switch)
        coverPreview = findViewById(R.id.cover_preview)
        selectedFileText = findViewById(R.id.selected_file_text)
        bookTitleText = findViewById(R.id.book_title_text)
        statusText = findViewById(R.id.status_text)
        storagePermissionStatusText = findViewById(R.id.storage_permission_status_text)
        storagePermissionButton = findViewById(R.id.storage_permission_button)
        readerStatusText = findViewById(R.id.reader_status_text)
        usageAccessButton = findViewById(R.id.usage_access_button)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        openButton.setOnClickListener { openEpubPicker() }
        syncButton.setOnClickListener { syncCurrentCover() }
        storagePermissionButton.setOnClickListener { openStoragePermissionSettings() }
        usageAccessButton.setOnClickListener { openUsageAccessSettings() }
        updateStoragePermissionStatus()
        updateReaderPermissionStatus()
        loadRecentResult()
    }

    override fun onResume() {
        super.onResume()
        if (::storagePermissionStatusText.isInitialized) {
            updateStoragePermissionStatus()
            updateReaderPermissionStatus()
        }
    }

    private fun updateStoragePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            storagePermissionStatusText.text = if (granted) {
                "公共儲存空間權限：已授權，可嘗試寫入 Pictures/BookCover。"
            } else {
                "公共儲存空間權限：未授權。App 會改用 app-specific Pictures 路徑；不可宣稱公共路徑可寫。"
            }
            storagePermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        } else {
            storagePermissionStatusText.text =
                "公共儲存空間權限：Android 11 以下不使用 MANAGE_EXTERNAL_STORAGE。"
            storagePermissionButton.visibility = View.GONE
        }
    }

    private fun openStoragePermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appUri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun updateReaderPermissionStatus() {
        val usageAccessGranted = hasUsageAccess()
        readerStatusText.text = if (usageAccessGranted) {
            "NeoReader 定位：已驗證需要 DUMP + Usage Access；Usage Access 已授權。DUMP 需一次性 ADB grant。"
        } else {
            "NeoReader 定位：已驗證需要 DUMP + Usage Access；Usage Access 尚未授權。DUMP 需一次性 ADB grant。"
        }
        usageAccessButton.visibility = View.VISIBLE
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            readerStatusText.text =
                "NeoReader 定位：找不到 Usage Access 設定頁；請在 BOOX 設定搜尋「Usage Access」。"
        }
    }

    private fun openEpubPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = EPUB_MIME_TYPE
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(EPUB_MIME_TYPE, "application/zip", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_EPUB)
    }

    @Deprecated("Deprecated Android callback retained for minSdk 26 without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_EPUB || resultCode != RESULT_OK) return

        val uri = data?.data ?: run {
            showError("檔案選擇器沒有回傳 URI，請重新選擇 EPUB。")
            return
        }
        persistReadPermission(uri, data.flags)
        selectedUri = uri
        selectedFileText.text = "檔案：${uri.lastPathSegment ?: uri}"
        extractCover(uri)
    }

    private fun persistReadPermission(uri: Uri, grantedFlags: Int) {
        val readFlags = grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlags == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, readFlags)
        } catch (_: SecurityException) {
            // Some providers offer only a transient grant. Extraction can still
            // proceed during this activity session, so this is informational.
        }
    }

    private fun extractCover(uri: Uri) {
        if (isBusy) return
        setBusy(true)
        statusText.text = "狀態：安全解析 EPUB 封面中…"

        Thread {
            try {
                val cover = EpubCoverExtractor.extract(contentResolver, uri)
                val bitmap = CoverImageProcessor.prepare(cover.bytes, ScaleMode.FIT_CENTER)
                runOnUiThread {
                    extractedCover = cover
                    preparedBitmap = bitmap
                    coverPreview.setImageBitmap(bitmap)
                    bookTitleText.text = "書名：${cover.title ?: "未知書名"}"
                    syncButton.isEnabled = true
                    setBusy(false)
                    statusText.text = "狀態：封面已準備，請確認開關後同步。"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false)
                    showError(formatError("解析 EPUB 失敗", error))
                }
            }
        }.start()
    }

    private fun syncCurrentCover() {
        val bitmap = preparedBitmap
        if (bitmap == null) {
            showError("尚未準備封面，請先選擇 EPUB。")
            return
        }
        if (!sleepSwitch.isChecked && !shutdownSwitch.isChecked) {
            showError("請至少開啟一種畫面同步。")
            return
        }
        if (isBusy) return

        setBusy(true)
        statusText.text = "狀態：寫入圖片並同步 BOOX…"
        val sleepEnabled = sleepSwitch.isChecked
        val shutdownEnabled = shutdownSwitch.isChecked

        Thread {
            try {
                val imageFile = CoverFileWriter.writeJpegAtomically(applicationContext, bitmap)
                val result = BooxScreensaverAdapter(applicationContext).sync(
                    imageFile,
                    sleepEnabled = sleepEnabled,
                    shutdownEnabled = shutdownEnabled,
                )
                runOnUiThread {
                    setBusy(false)
                    val message = formatSyncResult(imageFile, result)
                    statusText.text = message
                    saveRecentResult(result)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false)
                    val message = formatError("同步失敗", error)
                    showError(message)
                    saveRecentError(message)
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        openButton.isEnabled = !busy
        syncButton.isEnabled = !busy && preparedBitmap != null
        sleepSwitch.isEnabled = !busy
        shutdownSwitch.isEnabled = !busy
    }

    private fun showError(message: String) {
        statusText.text = "錯誤：$message"
    }

    private fun loadRecentResult() {
        val recent = preferences.getString(PREF_LAST_RESULT, null) ?: return
        statusText.text = "最近結果：$recent"
    }

    private fun saveRecentResult(result: SyncResult) {
        val summary = when (result) {
            is SyncResult.Success -> "${formatTimestamp()} 成功（type=${result.sentTypes.joinToString()}）"
            is SyncResult.Failure -> "${formatTimestamp()} 失敗：${result.message}"
        }
        preferences.edit().putString(PREF_LAST_RESULT, summary).apply()
    }

    private fun saveRecentError(message: String) {
        preferences.edit().putString(PREF_LAST_RESULT, "${formatTimestamp()} 失敗：$message").apply()
    }

    private fun formatSyncResult(imageFile: java.io.File, result: SyncResult): String =
        when (result) {
            is SyncResult.Success ->
                "狀態：${formatTimestamp()} 同步完成。\n" +
                    "已送出 type=${result.sentTypes.joinToString()}。\n" +
                    "輸出：${imageFile.absolutePath}"

            is SyncResult.Failure ->
                "錯誤：${result.message}\n" +
                    "已送出 type=${result.sentTypes.joinToString().ifBlank { "無" }}。\n" +
                    "輸出：${imageFile.absolutePath}"
        }

    private fun formatError(prefix: String, error: Exception): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        return "$prefix：$detail。請確認檔案仍可讀且未損壞。"
    }

    private fun formatTimestamp(): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())

    companion object {
        private const val REQUEST_OPEN_EPUB = 1001
        private const val EPUB_MIME_TYPE = "application/epub+zip"
        private const val PREFERENCES_NAME = "cover_sync_preferences"
        private const val PREF_LAST_RESULT = "last_result"
    }
}
