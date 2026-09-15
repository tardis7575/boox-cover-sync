package tw.mustp.booxcoversync.autosync

import android.net.Uri
import java.security.MessageDigest
import tw.mustp.booxcoversync.reader.NeoReaderLocation
import tw.mustp.booxcoversync.reader.NeoReaderLocationResult
import tw.mustp.booxcoversync.reader.NeoReaderLocator

/** A cancellable unit of work returned by [AutoSyncScheduler]. */
fun interface AutoSyncCancellable {
    fun cancel()
}

/**
 * Small scheduling seam so the state machine can be tested without sleeping.
 * Implementations should run [task] once after [delayMillis], unless cancelled.
 */
fun interface AutoSyncScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): AutoSyncCancellable
}

/** Stores only the fingerprint of the last successfully synchronised URI. */
interface AutoSyncFingerprintStore {
    fun read(): String?

    fun write(fingerprint: String)
}

/** SHA-256 identity for a content URI; the URI itself is never persisted or logged. */
object AutoSyncUriFingerprint {
    fun of(uri: Uri): String = digest(uri.toString())

    internal fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Event-driven coordinator for automatic cover synchronisation.
 *
 * A NeoReader window event schedules one debounced provider lookup. Missing or
 * unchanged results are retried at the configured delays because BOOX Metadata
 * can briefly continue reporting the previous book. Hard failures stop immediately.
 */
class AutoSyncCoordinator(
    private val scheduler: AutoSyncScheduler,
    private val locator: NeoReaderLocator,
    private val fingerprintStore: AutoSyncFingerprintStore,
    private val onNewLocation: (location: NeoReaderLocation, fingerprint: String, generation: Long) -> Unit,
    enabled: Boolean = true,
    private val fingerprintForUri: (Uri) -> String = AutoSyncUriFingerprint::of,
    private val enabledProvider: () -> Boolean = { true },
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val retryOffsetsMillis: List<Long> = DEFAULT_RETRY_OFFSETS_MILLIS,
) {
    private val stateLock = Any()
    private var generation = 0L
    private var screenOn = true
    private var enabled = enabled
    private var pending: AutoSyncCancellable? = null
    private var inFlight: InFlight? = null
    private var recheckRequested = false


    /** Called only after an AccessibilityService filters a NeoReader window event. */
    fun onReaderWindowChanged() {
        synchronized(stateLock) {
            cancelPendingLocked()
            if (!screenOn || !enabled || !isExternallyEnabledLocked()) return
            if (inFlight != null) {
                recheckRequested = true
                return
            }
            scheduleLookupLocked(debounceMillis)
        }
    }

    /** Cancels delayed lookups when the device screen turns off. */
    fun onScreenOff() {
        synchronized(stateLock) {
            screenOn = false
            generation += 1
            cancelPendingLocked()
            inFlight = null
            recheckRequested = false
        }
    }

    /** Allows a later Accessibility event to start a new lookup. */
    fun onScreenOn() {
        synchronized(stateLock) {
            screenOn = true
        }
    }

    /** Enables or disables automatic work without requiring service restart. */
    fun setEnabled(value: Boolean) {
        synchronized(stateLock) {
            if (enabled == value) return
            enabled = value
            if (!value) {
                generation += 1
                cancelPendingLocked()
                inFlight = null
                recheckRequested = false
            }
        }
    }

    /** True only while the candidate still belongs to the current active event. */
    fun isProcessingAllowed(candidateFingerprint: String, candidateGeneration: Long): Boolean =
        synchronized(stateLock) {
            isProcessingAllowedLocked(candidateFingerprint, candidateGeneration)
        }

    /**
     * Runs the final BOOX broadcast while holding the same state lock used by
     * screen/toggle cancellation. This prevents a broadcast after cancellation
     * has won the race with the pipeline.
     */
    fun <T> withProcessingPermission(
        candidateFingerprint: String,
        candidateGeneration: Long,
        action: () -> T,
    ): T? = synchronized(stateLock) {
        if (!isProcessingAllowedLocked(candidateFingerprint, candidateGeneration)) {
            null
        } else {
            action()
        }
    }

    /**
     * Commits a candidate only when its lookup generation is still current and
     * the pipeline completed successfully. Failed processing is deliberately
     * not remembered, so a later event can retry it.
     */
    fun onProcessingCompleted(
        fingerprint: String,
        generation: Long,
        success: Boolean,
    ) {
        synchronized(stateLock) {
            val current = inFlight
            if (current == null || current.fingerprint != fingerprint || current.generation != generation) {
                return
            }

            val shouldCommit = success && isProcessingAllowedLocked(fingerprint, generation)
            val shouldRecheck = recheckRequested && enabled && screenOn && isExternallyEnabledLocked()
            inFlight = null
            recheckRequested = false
            if (shouldCommit) {
                fingerprintStore.write(fingerprint)
            }
            if (shouldRecheck) {
                scheduleLookupLocked(debounceMillis)
            }
        }
    }

    fun close() {
        synchronized(stateLock) {
            generation += 1
            cancelPendingLocked()
            inFlight = null
            recheckRequested = false
        }
    }

    private fun attempt(eventGeneration: Long, retryIndex: Int) {
        synchronized(stateLock) {
            pending = null
            if (!isCurrentLocked(eventGeneration)) return
        }

        when (val result = try {
            locator.locate()
        } catch (_: Exception) {
            // Locator failures are fail-closed. Do not retry unknown failures.
            return
        }) {
            is NeoReaderLocationResult.Found -> handleFound(result.location, eventGeneration, retryIndex)
            is NeoReaderLocationResult.NotFound -> scheduleRetry(eventGeneration, retryIndex)
            is NeoReaderLocationResult.PermissionDenied,
            is NeoReaderLocationResult.UsageStatsPermissionDenied,
            is NeoReaderLocationResult.CommandFailed,
            -> return
        }
    }

    private fun handleFound(location: NeoReaderLocation, eventGeneration: Long, retryIndex: Int) {
        val fingerprint = try {
            fingerprintForUri(location.contentUri)
        } catch (_: Exception) {
            return
        }

        var unchanged = false
        val shouldDispatch = synchronized(stateLock) {
            if (!isCurrentLocked(eventGeneration)) {
                false
            } else if (fingerprint == fingerprintStore.read()) {
                unchanged = true
                false
            } else {
                val current = inFlight
                if (current?.fingerprint == fingerprint && current.generation == eventGeneration) {
                    false
                } else {
                    inFlight = InFlight(fingerprint = fingerprint, generation = eventGeneration)
                    true
                }
            }
        }
        if (unchanged) scheduleRetry(eventGeneration, retryIndex)
        if (shouldDispatch) onNewLocation(location, fingerprint, eventGeneration)
    }

    private fun scheduleRetry(eventGeneration: Long, retryIndex: Int) {
        val targetOffset = retryOffsetsMillis.getOrNull(retryIndex) ?: return
        val previousOffset = if (retryIndex == 0) {
            0L
        } else {
            retryOffsetsMillis.getOrNull(retryIndex - 1) ?: return
        }
        if (targetOffset <= previousOffset) return
        val delay = targetOffset - previousOffset
        synchronized(stateLock) {
            if (!isCurrentLocked(eventGeneration)) return
            pending = scheduler.schedule(delay) {
                attempt(eventGeneration, retryIndex + 1)
            }
        }
    }

    private fun isCurrentLocked(eventGeneration: Long): Boolean =
        enabled && screenOn && generation == eventGeneration && isExternallyEnabledLocked()

    private fun isProcessingAllowedLocked(candidateFingerprint: String, candidateGeneration: Long): Boolean {
        val current = inFlight
        return enabled && screenOn && generation == candidateGeneration && isExternallyEnabledLocked() &&
            current?.fingerprint == candidateFingerprint && current.generation == candidateGeneration
    }

    private fun isExternallyEnabledLocked(): Boolean = try {
        enabledProvider()
    } catch (_: RuntimeException) {
        false
    }

    private fun cancelPendingLocked() {
        pending?.cancel()
        pending = null
    }

    private fun scheduleLookupLocked(delayMillis: Long) {
        generation += 1
        val eventGeneration = generation
        pending = scheduler.schedule(delayMillis) {
            attempt(eventGeneration, retryIndex = 0)
        }
    }

    private data class InFlight(
        val fingerprint: String,
        val generation: Long,
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 2_000L
        // Retry offsets from the first locate: +5s and +15s. The second
        // scheduled delay is therefore 10s after the first retry, not 15s.
        val DEFAULT_RETRY_OFFSETS_MILLIS = listOf(5_000L, 15_000L)
    }
}
