package io.github.rhczz.inkotp.app

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.interchange.InterchangeCodec
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.data.*
import java.security.MessageDigest
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** UI orchestration around the real codecs; cryptographic primitives are covered in core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ViewModelInterchangeTest {
    private val store = ViewModelStore()
    private val files = MemoryFiles()
    private val repository = VaultRepository(files, MemoryEnvelopes(), NoHardwareKeys,
        kdf = FastTestKdf, kdfParams = KdfParams(8192, 1, 1))
    private val password = "correct horse battery staple"

    @After fun cleanup() {
        store.clear()
        pumpUntil { repository.model == null }
    }

    @Test fun allFormatsAreStagedWithMetadataAndClearedOnSessionLock() {
        val vm = unlocked()
        for (format in InterchangeFormat.entries) {
            var token: Long? = null
            vm.export(password, format) { token = it }
            pumpUntil { !vm.state.busy }
            assertNull("${format}: ${vm.state.error}", vm.state.error)
            assertNotNull(token)
            assertEquals(format, vm.describeExport(token!!)!!.format)
            val ticket = vm.takeExport(token!!)!!
            assertEquals(format, InterchangeCodec.detect(ticket.bytes))
            val decoded = InterchangeCodec.decode(ticket.bytes,
                if (format.requiresPassword) password.toCharArray() else null)
            assertEquals("local", decoded.model.items.single().accountName)
            repository.discardModel(decoded.model)
            ticket.bytes.fill(0)
            assertNull(vm.takeExport(token!!))
        }
        var token: Long? = null
        vm.export(password, InterchangeFormat.OTPAUTH_TEXT) { token = it }
        pumpUntil { !vm.state.busy }
        vm.lock()
        assertNull(vm.describeExport(token!!))
        assertNull(vm.takeExport(token!!))
    }

    @Test fun wrongLocalPasswordCannotStageEvenAPlaintextExport() {
        val vm = unlocked()
        var callbacks = 0
        vm.export("wrong", InterchangeFormat.OTPAUTH_TEXT) { callbacks++ }
        pumpUntil { !vm.state.busy }
        assertEquals(0, callbacks)
        assertTrue(vm.state.error!!.contains("恢复口令不正确"))
        assertNull(vm.describeExport(vm.sessionToken))
        assertEquals(1, repository.model!!.items.size)
    }

    @Test fun plaintextPreviewDoesNotMutateTheVaultAndReplaceKeepsLocalRecoveryPassword() {
        val vm = unlocked()
        val originalBytes = files.bytes!!.copyOf()
        val input = "otpauth://totp/Example:remote?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example".toByteArray()
        vm.previewImport(input, "") {}
        pumpUntil { !vm.state.busy }
        assertTrue(input.all { it == 0.toByte() })
        assertEquals("OTPAUTH_TEXT", vm.state.importFormat)
        assertEquals(1, vm.state.importNames!!.size)
        assertArrayEquals(originalBytes, files.bytes)
        assertEquals("local", repository.model!!.items.single().accountName)

        vm.confirmImport(MergeConflictPolicy.KEEP_LOCAL, replace = true)
        pumpUntil { !vm.state.busy }
        assertNull(vm.state.error)
        assertNull(vm.state.importNames)
        assertNull(vm.state.importFormat)
        assertTrue(vm.state.importWarnings.isEmpty())
        assertEquals("remote", repository.model!!.items.single().accountName)
        assertTrue(runBlocking { repository.verifyPassword(password.toCharArray()) })
    }

    @Test fun malformedOrUnsupportedImportCannotEraseOrReplaceTheLocalVault() {
        val vm = unlocked()
        val original = files.bytes!!.copyOf()
        listOf("{broken", "otpauth://steam/remote?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").forEach { raw ->
            val input = raw.toByteArray()
            vm.previewImport(input, "") {}
            pumpUntil { !vm.state.busy }
            assertTrue(input.all { it == 0.toByte() })
            assertNotNull(vm.state.error)
            assertNull(vm.state.importNames)
            assertArrayEquals(original, files.bytes)
            assertEquals("local", repository.model!!.items.single().accountName)
        }
    }

    @Test fun cancellingPreviewDropsItsNamesFormatAndWarnings() {
        val vm = unlocked()
        val input = InterchangeCodec.encode(repository.model!!, InterchangeFormat.AEGIS_PLAINTEXT).bytes
        vm.previewImport(input, "") {}
        pumpUntil { !vm.state.busy }
        assertNotNull(vm.state.importNames)
        vm.cancelImport()
        assertNull(vm.state.importNames)
        assertNull(vm.state.importFormat)
        assertTrue(vm.state.importWarnings.isEmpty())
        assertEquals(1, repository.model!!.items.size)
    }

    private fun unlocked(): VaultViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("vault_metadata", 0).edit().clear().commit()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(app, repository) as T
        }
        val vm = ViewModelProvider(store, factory)[VaultViewModel::class.java]
        pumpUntil { vm.state.ready && !vm.state.busy }
        vm.initialize(password)
        pumpUntil { !vm.state.busy }
        assertTrue(vm.state.unlocked)
        vm.add("Example", "local", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") {}
        pumpUntil { !vm.state.busy }
        assertEquals(1, vm.state.items.size)
        return vm
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(2)
        }
        fail("asynchronous operation did not complete")
    }

    private class MemoryFiles : VaultFiles {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read() = bytes!!.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private class MemoryEnvelopes : EnvelopeStore {
        private var value: DeviceEnvelope? = null
        override suspend fun read() = value
        override suspend fun write(value: DeviceEnvelope?) { this.value = value }
    }
    private object NoHardwareKeys : DeviceKeys {
        override fun encryptCipher(mode: String): Cipher = error("no hardware")
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher = error("no hardware")
        override fun delete(mode: String) = Unit
    }
    private object FastTestKdf : Kdf {
        override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password.concatToString().toByteArray() + salt)
    }
}
