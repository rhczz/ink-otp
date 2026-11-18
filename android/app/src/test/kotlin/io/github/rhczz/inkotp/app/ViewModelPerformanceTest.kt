package io.github.rhczz.inkotp.app

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.data.DeviceEnvelope
import io.github.rhczz.inkotp.data.DeviceKeys
import io.github.rhczz.inkotp.data.EnvelopeStore
import io.github.rhczz.inkotp.data.VaultFiles
import io.github.rhczz.inkotp.data.VaultRepository
import io.github.rhczz.inkotp.perf.ProjectionCacheStats
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPausedMessageQueue

/** Real VM/repository and real main-loop timers, without activities or hardware keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ViewModelPerformanceTest {
    private val store = ViewModelStore()
    private val realNanoClock = System::class.java.getMethod("nanoTime")
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextClockRead = AtomicReference<(() -> Long)?>(null)
    private val repository = VaultRepository(MemoryFiles(), MemoryEnvelopes(), NoHardwareKeys,
        kdf = FastTestKdf, kdfParams = KdfParams(8192, 1, 1))

    @Before fun setAnAlignedClockAndClearLocalMetadata() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("vault_metadata", 0).edit().clear().commit()
        Settings.Global.putInt(application.contentResolver, Settings.Global.AUTO_TIME, 1)
        // The VM receives this controlled clock; production classes need no instrumentation.
        assertTrue(SystemClock.setCurrentTimeMillis(120_000))
    }

    @After fun clearTheSessionAndProbeScope() {
        store.clear()
        pumpUntil("serialized cleanup") { repository.model == null }
        probes.cancel()
    }

    @Test fun lockedAndNotYetForegroundSessionsHaveNoPollingMessagesOrOtpCalculations() {
        val vm = viewModel()
        assertFalse(vm.tickerActive)
        assertEquals(0L, statistics(vm).codeComputations)
        assertEquals(0, pendingMainTasks())

        advanceSeconds(5)

        assertFalse(vm.tickerActive)
        assertEquals(0L, statistics(vm).codeComputations)
        assertEquals(0, pendingMainTasks())
        vm.initialize(PASSWORD)
        pumpUntil("created but not foreground") { vm.state.unlocked && !vm.state.busy }
        assertFalse(vm.tickerActive)
        advanceSeconds(3)
        assertFalse(vm.tickerActive)
        assertEquals(0L, statistics(vm).codeComputations)
        assertEquals(0, pendingMainTasks())
    }

    @Test fun repeatedForegroundCallbacksKeepOneTimerAndOneHmacPerCounterWhileCountdownChanges() {
        val vm = unlockedViewModel()
        val initial = statistics(vm).codeComputations
        assertEquals(1L, initial)
        assertEquals(30, vm.state.items.single().remainingSeconds)
        assertFalse(vm.tickerActive)
        assertEquals(0, pendingMainTasks())

        vm.onForeground()
        pumpUntil("one delayed foreground ticker") { vm.tickerActive && pendingMainTasks() == 1 }
        repeat(20) { vm.onForeground() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("duplicate foreground callbacks must not queue extra tickers", 1, pendingMainTasks())

        for (remaining in 29 downTo 1) {
            advanceSeconds(1)
            pumpUntil("countdown $remaining") { vm.state.items.single().remainingSeconds == remaining }
            pumpUntil("one next-second message") { pendingMainTasks() == 1 }
        }
        assertEquals(initial, statistics(vm).codeComputations)
        assertEquals(1, pendingMainTasks())

        advanceSeconds(1)
        pumpUntil("new OTP counter") { vm.state.items.single().remainingSeconds == 30 }
        assertEquals(initial + 1, statistics(vm).codeComputations)
        pumpUntil("one ticker after period rollover") { pendingMainTasks() == 1 }
    }

    @Test fun backgroundGraceKeepsOnlyTheLockDeadlineAndManualLockClearsCachedCodes() {
        val vm = unlockedViewModel(graceSeconds = 30)
        vm.onForeground()
        pumpUntil("foreground ticker") { vm.tickerActive && pendingMainTasks() == 1 }
        vm.onBackground()
        pumpUntil("one grace-period lock timer") { !vm.tickerActive && pendingMainTasks() == 1 }
        val deadline = shadowOf(Looper.getMainLooper()).nextScheduledTaskTime
        val before = statistics(vm)
        val remaining = vm.state.items.single().remainingSeconds

        advanceSeconds(5)

        assertTrue("the 30-second grace period has not expired", vm.state.unlocked)
        assertFalse(vm.tickerActive)
        assertEquals(remaining, vm.state.items.single().remainingSeconds)
        assertEquals(before, statistics(vm))
        assertEquals(1, pendingMainTasks())
        assertEquals("background must not schedule one-second projection work", deadline,
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)

        vm.lock()
        assertTrue(vm.state.items.isEmpty())
        assertFalse(vm.tickerActive)
        pumpUntil("locked repository and cache") { repository.model == null }
        val cleared = statistics(vm)
        assertEquals(0, cleared.cachedEntries)
        assertEquals(0, cleared.cachedCodeCharacters)
        assertEquals(0, cleared.metadataCharacters)
        pumpUntil("no delayed work after manual lock") { pendingMainTasks() == 0 }
        advanceSeconds(3)
        assertEquals(cleared, statistics(vm))
        assertEquals(0, pendingMainTasks())
    }

    @Test fun diagnosticActionsRefreshOnlyTheirOwnResults() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putInt(application.contentResolver, Settings.Global.AUTO_TIME, 0)
        val vm = unlockedViewModel()
        vm.onForeground()
        pumpUntil("idle foreground ticker") { vm.tickerActive && pendingMainTasks() == 1 }
        val initial = statistics(vm).codeComputations
        Settings.Global.putInt(application.contentResolver, Settings.Global.AUTO_TIME, 1)
        assertTrue(vm.state.clockWarning)

        vm.refreshResources()
        pumpUntil("resource sample") { vm.state.resources != null && !vm.state.samplingResources }
        assertTrue(vm.state.clockWarning)
        assertEquals(initial, statistics(vm).codeComputations)
        val sample = vm.state.resources

        repeat(20) { vm.recalculateCodes() }
        pumpUntil("recalculated OTPs") { !vm.state.clockWarning }
        assertEquals(initial + 1, statistics(vm).codeComputations)
        assertSame(sample, vm.state.resources)
        assertFalse(vm.state.samplingResources)
    }

    @Test fun failedRecalculationCanRetryAndDoesNotRefreshResourceStatistics() {
        val vm = unlockedViewModel()
        vm.onForeground()
        pumpUntil("idle foreground ticker") { vm.tickerActive && pendingMainTasks() == 1 }
        val before = statistics(vm)
        nextClockRead.set { error("Unusable test clock") }

        vm.recalculateCodes()
        pumpUntil("recalculation failure") { vm.state.error == "验证码重新计算未完成，可稍后重试" }
        assertEquals(before.codeComputations, statistics(vm).codeComputations)
        assertNull(vm.state.resources)
        assertFalse(vm.state.samplingResources)

        vm.clearError()
        vm.recalculateCodes()
        assertEquals(before.codeComputations + 1, statistics(vm).codeComputations)
        assertNull(vm.state.error)
    }

    @Test fun lockingWhileRecalculatingDiscardsInFlightDiagnosticResults() {
        val vm = unlockedViewModel()
        vm.onForeground()
        pumpUntil("idle foreground ticker") { vm.tickerActive && pendingMainTasks() == 1 }
        val began = CountDownLatch(1)
        val complete = CountDownLatch(1)
        nextClockRead.set {
            began.countDown()
            check(complete.await(5, TimeUnit.SECONDS))
            SystemClock.uptimeMillis()
        }

        vm.recalculateCodes()
        try {
            pumpUntil("recalculation waiting for the clock") { began.count == 0L }
            vm.refreshResources()
            vm.lock()
        } finally {
            complete.countDown()
        }
        pumpUntil("serialized lock completes") { repository.model == null }
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertEquals(0, statistics(vm).cachedEntries)
        assertNull(vm.state.resources)
        assertFalse(vm.state.samplingResources)
    }

    private fun viewModel(): VaultViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VaultViewModel(application, repository, wallClockMillis = {
                    nextClockRead.getAndSet(null)?.invoke() ?: SystemClock.uptimeMillis()
                }) as T
        }
        return ViewModelProvider(store, factory)[VaultViewModel::class.java].also { vm ->
            pumpUntil("initial metadata") { vm.state.ready && !vm.state.busy }
        }
    }

    private fun unlockedViewModel(graceSeconds: Int = 0): VaultViewModel {
        val vm = viewModel()
        vm.initialize(PASSWORD)
        pumpUntil("password vault") { vm.state.unlocked && !vm.state.busy }
        vm.add("RFC 6238", "performance@example.test", PUBLIC_SEED) { }
        pumpUntil("saved public test account") { !vm.state.busy && vm.state.items.size == 1 }
        if (graceSeconds != 0) {
            vm.settings { it.copy(lockTimeoutSeconds = graceSeconds) }
            pumpUntil("grace setting") { !vm.state.busy && vm.state.settings.lockTimeoutSeconds == graceSeconds }
        }
        return vm
    }

    private fun statistics(vm: VaultViewModel): ProjectionCacheStats {
        val result = AtomicReference<ProjectionCacheStats?>()
        probes.launch { result.set(vm.projectionStatistics()) }
        // Do not runBlocking on main: the ticker may hold the gate while awaiting main delivery.
        pumpUntil("projection statistics") { result.get() != null }
        return requireNotNull(result.get())
    }

    private fun pendingMainTasks(): Int =
        (shadowOf(Looper.getMainLooper().queue) as ShadowPausedMessageQueue).internalGetSize()

    private fun advanceSeconds(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        // Keep timeout accounting independent of the injected, deliberately paused clock.
        val deadline = (realNanoClock.invoke(null) as Long) + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while ((realNanoClock.invoke(null) as Long) < deadline)
        fail("Timed out waiting for $description")
    }

    private class MemoryFiles : VaultFiles {
        @Volatile private var contents: ByteArray? = null
        override fun exists() = contents != null
        override fun read() = requireNotNull(contents).copyOf()
        override fun write(bytes: ByteArray) { contents = bytes.copyOf() }
    }

    private class MemoryEnvelopes : EnvelopeStore {
        private var value: DeviceEnvelope? = null
        override suspend fun read() = value
        override suspend fun write(value: DeviceEnvelope?) { this.value = value }
    }

    private object FastTestKdf : Kdf {
        override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password.concatToString().toByteArray() + salt)
    }

    private object NoHardwareKeys : DeviceKeys {
        override fun encryptCipher(mode: String): Cipher = error("password-only performance test")
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher = error("password-only performance test")
        override fun delete(mode: String) = Unit
    }

    private companion object {
        const val PASSWORD = "public performance passphrase"
        const val PUBLIC_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
