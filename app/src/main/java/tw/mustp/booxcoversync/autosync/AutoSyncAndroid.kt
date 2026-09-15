package tw.mustp.booxcoversync.autosync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import tw.mustp.booxcoversync.boox.BooxScreensaverAdapter
import tw.mustp.booxcoversync.boox.SyncResult
import tw.mustp.booxcoversync.epub.EpubCoverExtractor
import tw.mustp.booxcoversync.image.CoverFileWriter
import tw.mustp.booxcoversync.image.CoverImageProcessor
import tw.mustp.booxcoversync.reader.BooxMetadataContentObserver
import tw.mustp.booxcoversync.reader.BooxMetadataProviderLocator
import tw.mustp.booxcoversync.reader.NeoReaderLocation
import tw.mustp.booxcoversync.reader.NeoReaderLocationResult
import tw.mustp.booxcoversync.reader.NeoReaderLocator

/** Handler-backed scheduler used by the Android service. */
class HandlerAutoSyncScheduler(
    private val handler: Handler,
) : AutoSyncScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): AutoSyncCancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis)
        return AutoSyncCancellable { handler.removeCallbacks(runnable) }
    }
}

/** SharedPreferences persistence that stores only a fingerprint. */
class SharedPreferencesAutoSyncFingerprintStore(
    context: Context,
) : AutoSyncFingerprintStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY_LAST_FINGERPRINT, null)

    override fun write(fingerprint: String) {
        preferences.edit().putString(KEY_LAST_FINGERPRINT, fingerprint).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_sync_state"
        const val KEY_LAST_FINGERPRINT = "last_synced_uri_fingerprint_v1"
    }
}

/** HMAC identity with a per-install secret; the URI is never persisted. */
class HmacAutoSyncUriFingerprint(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secret: ByteArray by lazy {
        val existing = preferences.getString(KEY_SECRET, null)
        if (existing != null) {
            Base64.decode(existing, Base64.NO_WRAP)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { generated ->
                preferences.edit()
                    .putString(KEY_SECRET, Base64.encodeToString(generated, Base64.NO_WRAP))
                    .apply()
            }
        }
    }

    fun of(uri: android.net.Uri): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_sync_state"
        const val KEY_SECRET = "uri_fingerprint_hmac_key_v1"
    }
}

/** Extracts, scales, writes, and publishes one EPUB cover. */
class DefaultAutoSyncPipeline(
    private val context: Context,
    private val defaultPublish: (File) -> Boolean = { imageFile ->
        BooxScreensaverAdapter(context).sync(imageFile) is SyncResult.Success
    },
    private val inputSource: BooxEpubInputSource = BooxEpubInputSource(context),
) {
    fun sync(
        location: NeoReaderLocation,
        isAllowed: () -> Boolean = { true },
        publish: (File) -> Boolean = defaultPublish,
        stableKey: String? = null,
    ): Boolean {
        if (location.packageName != NEO_READER_PACKAGE ||
            location.action != ACTION_VIEW ||
            location.contentUri.scheme != CONTENT_SCHEME &&
                location.contentUri.scheme != FILE_SCHEME
        ) {
            return false
        }
        if (!isAllowed()) return false

        return try {
            val extracted = inputSource.open(location.contentUri).use { input ->
                EpubCoverExtractor.extract(input)
            }
            val bitmap = CoverImageProcessor.prepare(extracted.bytes)
            try {
                if (!isAllowed()) return false
                val imageFile = CoverFileWriter.writeJpegAtomically(
                    context,
                    bitmap,
                    stableKey = stableKey,
                )
                if (!isAllowed()) return false
                publish(imageFile)
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val CONTENT_SCHEME = "content"
        const val FILE_SCHEME = "file"
        const val NEO_READER_PACKAGE = "com.onyx.kreader"
    }
}


/**
 * Battery-friendly event entry point. It receives only window events from
 * NeoReader; event text and the Accessibility node tree are never inspected.
 */
class BooxCoverSyncAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var coordinator: AutoSyncCoordinator? = null
    private var metadataObserver: BooxMetadataContentObserver? = null
    private var screenInteractive = true
    private var screenReceiver: BroadcastReceiver? = null
    private var preferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            packageNames = arrayOf(NEO_READER_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500L
        }

        val locator: NeoReaderLocator = BooxMetadataProviderLocator(this)
        val stateStore = SharedPreferencesAutoSyncFingerprintStore(this)
        val hmacFingerprint = HmacAutoSyncUriFingerprint(this)
        val preferences = getSharedPreferences(COVER_SYNC_PREFERENCES, MODE_PRIVATE)
        screenInteractive =
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        val booxAdapter = BooxScreensaverAdapter(this)
        lateinit var pipeline: DefaultAutoSyncPipeline
        lateinit var created: AutoSyncCoordinator
        created = AutoSyncCoordinator(
            scheduler = HandlerAutoSyncScheduler(mainHandler),
            locator = locator,
            fingerprintStore = stateStore,
            enabled = preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false),
            fingerprintForUri = hmacFingerprint::of,
            enabledProvider = { preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false) },
            onNewLocation = { location, fingerprint, generation ->
                worker.execute {
                    val success = pipeline.sync(
                        location = location,
                        stableKey = fingerprint,
                        isAllowed = { created.isProcessingAllowed(fingerprint, generation) },
                        publish = { imageFile ->
                            created.setEnabled(preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false))
                            created.withProcessingPermission(fingerprint, generation) {
                                booxAdapter.sync(imageFile) is SyncResult.Success
                            } ?: false
                        },
                    )
                    mainHandler.post {
                        created.setEnabled(preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false))
                        created.onProcessingCompleted(fingerprint, generation, success)
                    }
                }
            },
        )
        pipeline = DefaultAutoSyncPipeline(this)
        coordinator = created
        val observer = BooxMetadataContentObserver(
            context = this,
            handler = mainHandler,
            onProviderChanged = created::onReaderWindowChanged,
        )
        metadataObserver = observer
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AUTO_SYNC_ENABLED_KEY) {
                val enabled = preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false)
                mainHandler.post {
                    created.setEnabled(enabled)
                    if (enabled && screenInteractive) observer.start() else observer.stop()
                }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenInteractive = false
                        created.onScreenOff()
                        observer.onScreenOff()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenInteractive = true
                        created.onScreenOn()
                        if (preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false)) {
                            observer.start()
                        }
                    }
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        if (screenInteractive && preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false)) {
            observer.start()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val preferences = getSharedPreferences(COVER_SYNC_PREFERENCES, MODE_PRIVATE)
        coordinator?.setEnabled(preferences.getBoolean(AUTO_SYNC_ENABLED_KEY, false))
        if (event.packageName?.toString() != NEO_READER_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        coordinator?.onReaderWindowChanged()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        metadataObserver?.stop()
        metadataObserver = null
        coordinator?.close()
        coordinator = null
        val preferences = getSharedPreferences(COVER_SYNC_PREFERENCES, MODE_PRIVATE)
        preferenceListener?.let { preferences.unregisterOnSharedPreferenceChangeListener(it) }
        preferenceListener = null
        screenReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // The receiver may not have been registered if setup failed.
            }
        }
        screenReceiver = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val NEO_READER_PACKAGE = "com.onyx.kreader"
        const val COVER_SYNC_PREFERENCES = "cover_sync_preferences"
        const val AUTO_SYNC_ENABLED_KEY = "auto_sync_enabled"
    }
}
