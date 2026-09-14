package tw.mustp.booxcoversync.autosync

import android.net.Uri
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tw.mustp.booxcoversync.reader.NeoReaderLocation
import tw.mustp.booxcoversync.reader.NeoReaderLocationResult
import tw.mustp.booxcoversync.reader.NeoReaderLocator

@RunWith(RobolectricTestRunner::class)
class AutoSyncCoordinatorTest {
    @Test
    fun `window events are debounced into one lookup`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(foundLocation("content://book/one")),
        )
        val store = FakeFingerprintStore()
        val found = mutableListOf<String>()
        val coordinator = coordinator(scheduler, locator, store) { _, fingerprint, generation ->
            found += "$fingerprint:$generation"
        }

        coordinator.onReaderWindowChanged()
        coordinator.onReaderWindowChanged()

        assertEquals(listOf(2_000L), scheduler.activeDelays())
        scheduler.runNext()

        assertEquals(1, locator.calls)
        assertEquals(1, found.size)
    }

    @Test
    fun `not found is retried at five and fifteen seconds only`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.NotFound("not ready"),
            NeoReaderLocationResult.NotFound("not ready"),
            NeoReaderLocationResult.NotFound("not ready"),
        )
        val coordinator = coordinator(scheduler, locator, FakeFingerprintStore())

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        assertEquals(listOf(5_000L), scheduler.activeDelays())
        scheduler.runNext()
        assertEquals(listOf(10_000L), scheduler.activeDelays())
        scheduler.runNext()

        assertEquals(3, locator.calls)
        assertTrue(scheduler.activeDelays().isEmpty())
    }

    @Test
    fun `screen off cancels debounce and prevents retry`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(NeoReaderLocationResult.NotFound("not ready"))
        val coordinator = coordinator(scheduler, locator, FakeFingerprintStore())

        coordinator.onReaderWindowChanged()
        coordinator.onScreenOff()
        scheduler.runAll()

        assertEquals(0, locator.calls)
    }

    @Test
    fun `permission failure is fail closed without retry`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(NeoReaderLocationResult.PermissionDenied("missing"))
        val coordinator = coordinator(scheduler, locator, FakeFingerprintStore())

        coordinator.onReaderWindowChanged()
        scheduler.runNext()

        assertEquals(1, locator.calls)
        assertTrue(scheduler.activeDelays().isEmpty())
    }

    @Test
    fun `not found retry offsets are five and fifteen seconds from first lookup`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.NotFound("not ready"),
            NeoReaderLocationResult.NotFound("not ready"),
            NeoReaderLocationResult.NotFound("not ready"),
        )
        val coordinator = coordinator(scheduler, locator, FakeFingerprintStore())

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        assertEquals(2_000L, scheduler.elapsedMillis)
        scheduler.runNext()
        assertEquals(7_000L, scheduler.elapsedMillis)
        scheduler.runNext()
        assertEquals(17_000L, scheduler.elapsedMillis)
    }

    @Test
    fun `disabled coordinator cancels pending work and ignores completion`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(foundLocation("content://book/one")),
        )
        val store = FakeFingerprintStore()
        var candidate: Candidate? = null
        val coordinator = coordinator(scheduler, locator, store, enabled = true) { _, fingerprint, generation ->
            candidate = Candidate(fingerprint, generation)
        }

        coordinator.onReaderWindowChanged()
        coordinator.setEnabled(false)
        scheduler.runAll()

        assertNull(candidate)
        assertNull(store.value)
    }

    @Test
    fun `external toggle blocks an in-flight pipeline before publish and commit`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(foundLocation("content://book/one")),
        )
        val store = FakeFingerprintStore()
        var externalEnabled = true
        var candidate: Candidate? = null
        val coordinator = coordinator(
            scheduler = scheduler,
            locator = locator,
            store = store,
            enabledProvider = { externalEnabled },
        ) { _, fingerprint, generation ->
            candidate = Candidate(fingerprint, generation)
        }

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        val current = requireNotNull(candidate)
        externalEnabled = false

        assertNull(
            coordinator.withProcessingPermission(current.fingerprint, current.generation) { true },
        )
        coordinator.onProcessingCompleted(current.fingerprint, current.generation, success = true)
        assertNull(store.value)
    }

    @Test
    fun `screen off blocks an in-flight pipeline before publish and commit`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(foundLocation("content://book/one")),
        )
        val store = FakeFingerprintStore()
        var candidate: Candidate? = null
        val coordinator = coordinator(scheduler, locator, store) { _, fingerprint, generation ->
            candidate = Candidate(fingerprint, generation)
        }

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        val current = requireNotNull(candidate)
        coordinator.onScreenOff()

        assertNull(
            coordinator.withProcessingPermission(current.fingerprint, current.generation) { true },
        )
        coordinator.onProcessingCompleted(current.fingerprint, current.generation, success = true)
        assertNull(store.value)
    }

    @Test
    fun `same fingerprint is not processed twice`() {
        val scheduler = FakeScheduler()
        val location = foundLocation("content://book/one")
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(location),
            NeoReaderLocationResult.Found(location),
        )
        val store = FakeFingerprintStore()
        val found = mutableListOf<String>()
        lateinit var coordinator: AutoSyncCoordinator
        coordinator = coordinator(scheduler, locator, store) { _, fingerprint, generation ->
            found += "$fingerprint:$generation"
            coordinator.onProcessingCompleted(fingerprint, generation, success = true)
        }

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        coordinator.onReaderWindowChanged()
        scheduler.runNext()

        assertEquals(1, found.size)
        assertEquals(found.single().substringBefore(':'), store.value)
    }

    @Test
    fun `failed processing does not commit fingerprint`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(NeoReaderLocationResult.Found(foundLocation("content://book/one")))
        val store = FakeFingerprintStore()
        var candidate: Candidate? = null
        val coordinator = coordinator(scheduler, locator, store) { _, fingerprint, generation ->
            candidate = Candidate(fingerprint, generation)
        }

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        val current = requireNotNull(candidate)
        coordinator.onProcessingCompleted(current.fingerprint, current.generation, success = false)

        assertNull(store.value)
    }

    @Test
    fun `stale completion cannot commit after a newer event`() {
        val scheduler = FakeScheduler()
        val locator = QueueLocator(
            NeoReaderLocationResult.Found(foundLocation("content://book/one")),
            NeoReaderLocationResult.Found(foundLocation("content://book/two")),
        )
        val store = FakeFingerprintStore()
        val candidates = ArrayDeque<Candidate>()
        val coordinator = coordinator(scheduler, locator, store) { _, fingerprint, generation ->
            candidates.add(Candidate(fingerprint, generation))
        }

        coordinator.onReaderWindowChanged()
        scheduler.runNext()
        coordinator.onReaderWindowChanged()
        scheduler.runNext()

        val first = candidates.removeFirst()
        val second = candidates.removeFirst()
        coordinator.onProcessingCompleted(first.fingerprint, first.generation, success = true)
        assertNull(store.value)
        coordinator.onProcessingCompleted(second.fingerprint, second.generation, success = true)
        assertEquals(second.fingerprint, store.value)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    private fun coordinator(
        scheduler: FakeScheduler,
        locator: NeoReaderLocator,
        store: FakeFingerprintStore,
        enabled: Boolean = true,
        enabledProvider: () -> Boolean = { true },
        onNewLocation: (NeoReaderLocation, String, Long) -> Unit = { _, _, _ -> },
    ): AutoSyncCoordinator = AutoSyncCoordinator(
        scheduler = scheduler,
        locator = locator,
        fingerprintStore = store,
        enabled = enabled,
        enabledProvider = enabledProvider,
        onNewLocation = onNewLocation,
    )

    private fun foundLocation(uri: String): NeoReaderLocation = NeoReaderLocation(
        action = "android.intent.action.VIEW",
        contentUri = Uri.parse(uri),
        packageName = "com.onyx.kreader",
        activityName = null,
    )

    private data class Candidate(val fingerprint: String, val generation: Long)

    private class QueueLocator(vararg results: NeoReaderLocationResult) : NeoReaderLocator {
        private val results = ArrayDeque(results.toList())
        var calls: Int = 0
            private set

        override fun locate(): NeoReaderLocationResult {
            calls += 1
            return if (results.isEmpty()) {
                NeoReaderLocationResult.NotFound("empty")
            } else {
                results.removeFirst()
            }
        }
    }

    private class FakeFingerprintStore : AutoSyncFingerprintStore {
        var value: String? = null

        override fun read(): String? = value

        override fun write(fingerprint: String) {
            value = fingerprint
        }
    }

    private class FakeScheduler : AutoSyncScheduler {
        private data class Task(
            val delayMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        var elapsedMillis: Long = 0L

        override fun schedule(delayMillis: Long, task: () -> Unit): AutoSyncCancellable {
            val scheduled = Task(delayMillis, task)
            tasks += scheduled
            return AutoSyncCancellable { scheduled.cancelled = true }
        }

        fun activeDelays(): List<Long> = tasks.filterNot { it.cancelled }.map { it.delayMillis }

        fun runNext() {
            val task = tasks.firstOrNull { !it.cancelled }
                ?: error("No active task")
            task.cancelled = true
            elapsedMillis += task.delayMillis
            task.action()
        }

        fun runAll() {
            while (tasks.any { !it.cancelled }) runNext()
        }
    }
}
