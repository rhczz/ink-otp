package io.github.rhczz.inkotp.app

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.crypto.RandomSource
import io.github.rhczz.inkotp.core.crypto.SecureRandomSource
import io.github.rhczz.inkotp.core.interchange.InterchangeCodec
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.uri.GoogleMigrationCodec
import io.github.rhczz.inkotp.data.DeviceEnvelope
import io.github.rhczz.inkotp.data.DeviceKeys
import io.github.rhczz.inkotp.data.EnvelopeStore
import io.github.rhczz.inkotp.data.VaultFiles
import io.github.rhczz.inkotp.data.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher

/** Real ViewModel/repository, with controllable slow platform boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class VaultViewModelLifecycleTest {
    private val files = MemoryFiles()
    private val kdf = BlockingKdf()
    private val random = BlockingRandom()
    private val store = ViewModelStore()
    private val blocks = mutableListOf<Block>()
    private val envelopes = MemoryEnvelopes()
    private val repository = VaultRepository(files, envelopes, NoDeviceKeys,
        kdf = kdf, kdfParams = KdfParams(8192, 1, 1), random = random)
    private val password = "correct horse battery staple"

    @Before fun clearBackupMetadata() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("vault_metadata", 0).edit().clear().commit()
    }

    @After fun clearViewModel() {
        blocks.forEach { it.release.countDown() }
        store.clear()
        pumpUntil("cleared repository") { repository.model == null }
    }

    @Test fun homeDuringInitializationNeverPublishesAnUnlockedSession() {
        val vm = viewModel()
        val slow = blockKdf()
        vm.initialize(password)
        pumpUntil("initialization KDF started") { slow.started.count == 0L }
        vm.onBackground()
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        slow.release.countDown()
        slow.awaitFinished()
        pumpFor(150)
        assertFalse(files.exists())
        assertNull(repository.model)
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
    }

    @Test fun homeDuringPasswordUnlockCannotPublishItsLateResult() {
        seedLockedVault()
        runBlocking { envelopes.write(null) }
        val vm = viewModel()
        val slow = blockKdf()
        vm.unlockPassword(password)
        pumpUntil("unlock KDF started") { slow.started.count == 0L }
        vm.onBackground()
        slow.release.countDown()
        slow.awaitFinished()
        pumpFor(150)
        assertNull(repository.model)
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        // Cancellation preserves the original committed vault for the next unlock.
        vm.unlockPassword(password)
        pumpUntil("next foreground unlock") { vm.state.unlocked && !vm.state.busy }
        assertEquals(1, vm.state.items.size)
    }

    @Test fun lockHidesItemsImmediatelyAndSuppressesThePendingAddCallback() {
        val vm = unlockedViewModel()
        val retainedSecret = repository.model!!.items.first().secret
        val slow = blockWrite()
        var callbacks = 0
        vm.add("Example", "second", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") { callbacks++ }
        pumpUntil("save started") { slow.started.count == 0L }
        vm.lock()
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertNull(vm.state.importNames)
        slow.release.countDown()
        pumpUntil("serialized lock cleanup") { repository.model == null }
        assertEquals(0, callbacks)
        assertTrue(retainedSecret.all { it == 0.toByte() })
        assertTrue(vm.state.items.isEmpty())
    }

    @Test fun lockDuringExportNeverStartsTheFilePickerCallback() {
        val vm = unlockedViewModel()
        val retainedSecret = repository.model!!.items.first().secret
        val slow = blockKdf()
        var callbacks = 0
        vm.export(password) { callbacks++ }
        pumpUntil("export KDF started") { slow.started.count == 0L }
        vm.lock()
        assertTrue(vm.state.items.isEmpty())
        slow.release.countDown()
        pumpUntil("export cancellation cleanup") { repository.model == null }
        assertEquals(0, callbacks)
        assertTrue(retainedSecret.all { it == 0.toByte() })
    }

    @Test fun homeImmediatelyLocksANewVaultByDefault() {
        val vm = unlockedViewModel()
        assertEquals(0, vm.state.settings.lockTimeoutSeconds)
        vm.onBackground()
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        pumpUntil("background cleanup") { repository.model == null }
    }

    @Test fun cancellingPreviewDuringConfirmationCannotEraseTheRemoteSecretsBeingImported() {
        val vm = unlockedViewModel()
        val remote = repository.model!!.copy(items = repository.model!!.items.map {
            it.copy(id = UUID.randomUUID().toString(), accountName = "imported", secret = it.secret.copyOf())
        })
        val backup = InterchangeCodec.encode(remote, InterchangeFormat.AEGIS_ENCRYPTED, password.toCharArray()).bytes
        repository.discardModel(remote)
        vm.previewImport(backup, password) { }
        pumpUntil("import preview") { vm.state.importNames != null && !vm.state.busy }
        // This boundary is before import copies the preview's secret arrays.
        val slow = Block().also { blocks += it; random.next.set(it) }
        vm.confirmImport(MergeConflictPolicy.KEEP_LOCAL, replace = false)
        pumpUntil("import confirmation started") { slow.started.count == 0L }
        vm.cancelImport()
        slow.release.countDown()
        pumpUntil("committed import") { !vm.state.busy && vm.state.notice != null }
        assertEquals(2, repository.model!!.items.size)
        assertTrue(repository.model!!.items.all { item -> item.secret.any { it != 0.toByte() } })
    }

    @Test fun initializationCommittedBeforeCancellationIsRecognizedWithoutRestartingTheApp() {
        val vm = viewModel()
        val slow = Block().also { blocks += it; files.afterWrite.set(it) }
        vm.initialize(password)
        pumpUntil("initial vault committed") { slow.started.count == 0L }
        assertTrue(files.exists())

        vm.onBackground()
        slow.release.countDown()

        pumpUntil("locked metadata reloaded") { repository.model == null && vm.state.exists && !vm.state.busy }
        assertTrue(vm.state.passwordSet)
        assertFalse(vm.state.unlocked)
        vm.unlockPassword(password)
        pumpUntil("committed vault can unlock") { vm.state.unlocked && !vm.state.busy }
    }

    @Test fun pickerResultExpiresByElapsedTimeBeforeAnyQueuedTimerRuns() {
        val vm = unlockedViewModel()
        val ticket = vm.beginPicker()
        vm.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))

        assertFalse(vm.endPicker(ticket))
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
    }

    @Test fun returningToForegroundAlsoChecksTheAbsolutePickerDeadline() {
        val vm = unlockedViewModel()
        vm.beginPicker()
        vm.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))

        vm.onForeground()

        assertFalse(vm.state.unlocked)
    }

    @Test fun oldPickerResultCannotEndThePickerOfANewerUnlockedSession() {
        val vm = unlockedViewModel()
        val old = vm.beginPicker()
        vm.lock()
        pumpUntil("old session closed") { repository.model == null }
        vm.unlockPassword(password)
        pumpUntil("new session unlocked") { vm.state.unlocked && !vm.state.busy }
        val fresh = vm.beginPicker()

        assertFalse(vm.endPicker(old))
        assertTrue(vm.isCurrentSession(fresh))
        assertTrue(vm.endPicker(fresh))
    }

    @Test fun completeGoogleBatchIsReviewedBeforeOneMerge() {
        val vm = unlockedViewModel()
        val sample = repository.model!!.items.single()
        val accounts = (1..11).map { sample.copy(accountName = "google-$it", sortIndex = it) }
        val pages = GoogleMigrationCodec.encode(accounts, 48)

        vm.ingestGoogleImport(pages[1].decodeToString())
        pumpUntil("waiting for remaining migration pages") { vm.state.googleImportProgress?.received == 1 && !vm.state.busy }
        assertNull(vm.state.importNames)
        assertEquals(1, repository.model!!.items.size)

        vm.ingestGoogleImport(pages[0].decodeToString())
        pumpUntil("all migration pages ready to review") { vm.state.importNames?.size == 11 && !vm.state.busy }
        assertEquals(GOOGLE_IMPORT_SOURCE, vm.state.importFormat)
        assertEquals(accounts.map { it.accountName }, vm.state.importNames!!.map { it.substringAfter(" · ") })
        assertEquals(1, repository.model!!.items.size)

        vm.confirmImport(MergeConflictPolicy.KEEP_LOCAL, replace = false)
        pumpUntil("migration committed") { vm.state.notice != null && !vm.state.busy }
        assertEquals(12, repository.model!!.items.size)
        assertNull(vm.state.importNames)
        assertNull(vm.state.googleImportProgress)
    }

    @Test fun googleCancelAndLockDiscardCollectedPages() {
        val vm = unlockedViewModel()
        val sample = repository.model!!.items.single()
        val accounts = (1..11).map { sample.copy(accountName = "google-$it", sortIndex = it) }
        val pages = GoogleMigrationCodec.encode(accounts, 48)

        vm.ingestGoogleImport(pages[0].decodeToString())
        pumpUntil("waiting for remaining migration pages") { vm.state.googleImportProgress?.received == 1 && !vm.state.busy }
        vm.cancelImport()
        assertNull(vm.state.googleImportProgress)
        vm.ingestGoogleImport(pages[1].decodeToString())
        pumpUntil("new scan is still incomplete") { vm.state.googleImportProgress?.received == 1 && !vm.state.busy }
        assertNull(vm.state.importNames)

        vm.lock()
        assertNull(vm.state.googleImportProgress)
        pumpUntil("locked migration cleanup") { repository.model == null }
    }

    @Test fun googleExportRequiresPasswordAndExplicitPermissionForIncompatibleSubset() {
        val vm = unlockedViewModel()
        vm.onForeground()
        vm.add("Public", "incompatible",
            "otpauth://totp/incompatible?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&algorithm=SHA256") { }
        pumpUntil("incompatible account added") { vm.state.items.size == 2 && !vm.state.busy }
        var pendingReview: GoogleExportReview? = null
        vm.reviewGoogleExport { pendingReview = it }
        pumpUntil("migration reviewed") { pendingReview != null && !vm.state.busy }
        val review = pendingReview!!
        assertEquals(1, review.supported)
        assertEquals(2, review.total)
        assertTrue(review.issues.single().contains("SHA1"))

        vm.exportGoogle("wrong password", review, allowSubset = true) { fail("accepted wrong password") }
        pumpUntil("incorrect recovery password") { vm.state.error != null && !vm.state.busy }
        assertNull(vm.googleExportPage(0))

        vm.exportGoogle(password, review, allowSubset = false) { fail("ignored subset opt-in") }
        pumpUntil("subset needs consent") { vm.state.error != null && !vm.state.busy }
        assertNull(vm.googleExportPage(0))

        vm.exportGoogle(password, review, allowSubset = true) {}
        pumpUntil("compatibility-reviewed QR available") { vm.state.googleExportPageCount == 1 && !vm.state.busy }
        val decoded = GoogleMigrationCodec.decode(vm.googleExportPage(0)!!.decodeToString())
        assertEquals(1, decoded.entries.size)
        decoded.clear()

        vm.lock()
        assertEquals(0, vm.state.googleExportPageCount)
        assertNull(vm.googleExportPage(0))
    }

    @Test fun backgroundedGoogleExportClearsPagesAndSuppressesLateResults() {
        val vm = unlockedViewModel()
        vm.onForeground()
        var pendingReview: GoogleExportReview? = null
        vm.reviewGoogleExport { pendingReview = it }
        pumpUntil("migration reviewed") { pendingReview != null && !vm.state.busy }
        val review = pendingReview!!
        val slow = blockKdf()
        var callbacks = 0
        vm.exportGoogle(password, review, allowSubset = false) { callbacks++ }
        pumpUntil("export verification start") { slow.started.count == 0L }
        vm.onContentHidden()
        vm.onForeground()
        slow.release.countDown()
        pumpUntil("interrupted export finished") { !vm.state.busy }
        assertEquals(0, callbacks)
        assertNull(vm.googleExportPage(0))

        vm.onForeground()
        vm.exportGoogle(password, review, allowSubset = false) { callbacks++ }
        pumpUntil("foreground QR export") { vm.state.googleExportPageCount == 1 && !vm.state.busy }
        assertEquals(1, callbacks)
        vm.onContentHidden()
        assertEquals(0, vm.state.googleExportPageCount)
        assertNull(vm.googleExportPage(0))
    }

    @Test fun vaultEntryMotionIsConsumedAcrossActivityRecreationAndResetByUnlock() {
        val vm = unlockedViewModel()
        val token = vm.sessionToken
        assertTrue(vm.shouldAnimateVaultEntry(token))
        vm.markVaultEntryPlayed(token)
        assertFalse(viewModel().shouldAnimateVaultEntry(token))

        vm.lock()
        pumpUntil("locked before new entry") { repository.model == null && !vm.state.busy }
        vm.unlockPassword(password)
        pumpUntil("unlocked for new entry") { vm.state.unlocked && !vm.state.busy }
        assertTrue(vm.shouldAnimateVaultEntry(vm.sessionToken))
        assertFalse(vm.shouldAnimateVaultEntry(token))
    }

    @Test fun pendingExportSurvivesActivityScopeRecreationUntilThePickerReturns() {
        val vm = unlockedViewModel()
        var token: Long? = null
        vm.export(password) { token = it }
        pumpUntil("pending export") { token != null && !vm.state.busy }

        // A recreated Activity obtains the same retained ViewModel, without a Compose byte buffer.
        val recreatedHostVm = viewModel()
        val ticket = recreatedHostVm.takeExport(token!!)!!
        try {
            assertSame(vm, recreatedHostVm)
            assertEquals(1, InterchangeCodec.decode(ticket.bytes, password.toCharArray()).model.items.size)
            assertNull(recreatedHostVm.takeExport(token!!))
        } finally { ticket.bytes.fill(0) }
    }

    @Test fun lockingDiscardsAnExportStillWaitingForTheFilePicker() {
        val vm = unlockedViewModel()
        var token: Long? = null
        vm.export(password) { token = it }
        pumpUntil("pending export") { token != null && !vm.state.busy }

        vm.lock()

        assertNull(vm.takeExport(token!!))
        assertFalse(vm.isCurrentSession(token!!))
    }

    @Test fun writingAnOlderSnapshotDoesNotMarkNewerChangesAsBackedUp() {
        val vm = unlockedViewModel()
        var token: Long? = null
        vm.export(password) { token = it }
        pumpUntil("export snapshot") { token != null && !vm.state.busy }
        val ticket = vm.takeExport(token!!)!!
        try {
            vm.add("Example", "later", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") { }
            pumpUntil("later mutation") { !vm.state.busy && vm.state.items.size == 2 }

            assertTrue(vm.backupWritten(ticket))
            pumpUntil("backup metadata refreshed") { !vm.state.busy }

            assertTrue(vm.state.backupReminder)
        } finally { ticket.bytes.fill(0) }
    }

    @Test fun oldExportWriteCannotChangeBackupMetadataInANewSession() {
        val vm = unlockedViewModel()
        var token: Long? = null
        vm.export(password) { token = it }
        pumpUntil("export snapshot") { token != null && !vm.state.busy }
        val ticket = vm.takeExport(token!!)!!
        try {
            vm.lock()
            pumpUntil("export session closed") { repository.model == null }
            vm.unlockPassword(password)
            pumpUntil("different session unlocked") { vm.state.unlocked && !vm.state.busy }

            assertFalse(vm.backupWritten(ticket))
            assertNull(vm.state.settings.lastBackupAt)
        } finally { ticket.bytes.fill(0) }
    }

    @Test fun automaticLockPreservesTheCodePasteWindowButManualLockRequestsClipboardClear() {
        val vm = unlockedViewModel()
        vm.onBackground()
        assertFalse(vm.state.clearClipboardOnLock)
        pumpUntil("automatic lock cleaned") { repository.model == null }
        vm.unlockPassword(password)
        pumpUntil("manual lock session") { vm.state.unlocked && !vm.state.busy }

        vm.lock()

        assertTrue(vm.state.clearClipboardOnLock)
    }

    @Test fun unreadablePlatformEnvelopeStillAllowsPasswordRecoveryOnColdStart() {
        seedLockedVault()
        envelopes.failRead = true

        val vm = viewModel()

        assertTrue(vm.state.exists)
        assertTrue(vm.state.passwordSet)
        assertFalse(vm.state.biometricEnabled)
        vm.unlockPassword(password)
        pumpUntil("password recovery without platform metadata") { vm.state.unlocked && !vm.state.busy }
        assertEquals(1, vm.state.items.size)
    }

    @Test fun damagedExistingVaultNeverReturnsToCreationOrGetsOverwritten() {
        val corrupt = byteArrayOf(1, 2, 3)
        files.write(corrupt)

        val vm = viewModel()

        assertTrue(vm.state.exists)
        assertFalse(vm.state.unlocked)
        assertNotNull(vm.state.error)
        vm.initialize(password)
        pumpUntil("creation rejected for damaged existing file") { !vm.state.busy }
        assertTrue(vm.state.exists)
        assertArrayEquals(corrupt, files.read())
    }

    private fun viewModel(): VaultViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(application, repository) as T
        }
        val vm = ViewModelProvider(store, factory)[VaultViewModel::class.java]
        pumpUntil("initial load") { vm.state.ready && !vm.state.busy }
        return vm
    }

    private fun unlockedViewModel(): VaultViewModel {
        seedLockedVault()
        val vm = viewModel()
        vm.unlockPassword(password)
        pumpUntil("unlocked vault") { vm.state.unlocked && !vm.state.busy }
        assertEquals(1, vm.state.items.size)
        return vm
    }

    private fun seedLockedVault() = runBlocking {
        repository.initialize(password.toCharArray())
        val item = VaultItem(UUID.randomUUID().toString(), OtpType.TOTP, "Example", "first",
            "12345678901234567890".toByteArray(), sortIndex = 1000,
            createdAt = "2026-11-17T00:00:00Z", updatedAt = "2026-11-17T00:00:00Z")
        repository.save(repository.model!!.copy(items = listOf(item)))
        item.secret.fill(0)
        repository.lock()
    }

    private fun blockKdf() = Block().also { blocks += it; kdf.next.set(it) }
    private fun blockWrite() = Block().also { blocks += it; files.nextWrite.set(it) }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < end)
        fail("Timed out waiting for $description")
    }

    private fun pumpFor(milliseconds: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private class Block {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun awaitFinished() { assertTrue("operation did not leave slow boundary", finished.await(5, TimeUnit.SECONDS)) }
        fun enter() {
            started.countDown()
            assertTrue("test did not release slow boundary", release.await(5, TimeUnit.SECONDS))
        }
    }

    private class BlockingKdf : Kdf {
        val next = AtomicReference<Block?>()
        override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
            val block = next.getAndSet(null)
            try {
                block?.enter()
                return Argon2Kdf().deriveKey(password, salt, params)
            } finally { block?.finished?.countDown() }
        }
    }

    private class BlockingRandom : RandomSource {
        val next = AtomicReference<Block?>()
        override fun bytes(size: Int): ByteArray {
            val block = next.getAndSet(null)
            try { block?.enter(); return SecureRandomSource().bytes(size) }
            finally { block?.finished?.countDown() }
        }
    }

    private class MemoryFiles : VaultFiles {
        @Volatile private var bytes: ByteArray? = null
        val nextWrite = AtomicReference<Block?>()
        val afterWrite = AtomicReference<Block?>()
        override fun exists() = bytes != null
        override fun read() = bytes!!.copyOf()
        override fun write(bytes: ByteArray) {
            val block = nextWrite.getAndSet(null)
            val committed = afterWrite.getAndSet(null)
            try { block?.enter(); this.bytes = bytes.copyOf(); committed?.enter() }
            finally { block?.finished?.countDown(); committed?.finished?.countDown() }
        }
    }

    private class MemoryEnvelopes : EnvelopeStore {
        private var value: DeviceEnvelope? = null
        var failRead = false
        override suspend fun read(): DeviceEnvelope? {
            if (failRead) throw IOException("Unreadable platform metadata")
            return value
        }
        override suspend fun write(value: DeviceEnvelope?) { this.value = value }
    }

    private object NoDeviceKeys : DeviceKeys {
        override fun encryptCipher(mode: String): Cipher = error("password-only test")
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher = error("password-only test")
        override fun delete(mode: String) = Unit
    }
}
