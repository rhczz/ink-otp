package io.github.rhczz.inkotp.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnvelopePolicyStorageTest {
    @Test fun replacingOrRemovingAnEnvelopeNeverRemovesTheIndependentSystemPolicy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = DataStoreEnvelopeStore(context)
        try {
            val systemEnvelope = DeviceEnvelope("system-auth", ByteArray(12) { 1 }, ByteArray(80) { 2 }, ByteArray(16) { 3 })
            store.writeSystemConfiguration(systemEnvelope, true)
            assertEquals("system-auth", store.read()!!.mode)
            assertTrue(store.systemAuthRequired())
            store.write(null)
            assertNull(store.read())
            assertTrue(DataStoreEnvelopeStore(context).systemAuthRequired())
            // The existing M3 envelope remains readable, but cannot remove the new mandatory policy.
            store.write(DeviceEnvelope("biometric", ByteArray(12), ByteArray(80), ByteArray(16)))
            assertEquals("biometric", store.read()!!.mode)
            assertTrue(store.systemAuthRequired())
            val deviceEnvelope = DeviceEnvelope("device-local", ByteArray(12), ByteArray(80), ByteArray(16))
            store.writeSystemConfiguration(deviceEnvelope, false)
            assertFalse(store.systemAuthRequired())
            assertEquals("device-local", store.read()!!.mode)
        } finally { store.writeSystemConfiguration(null, false) }
    }
}
