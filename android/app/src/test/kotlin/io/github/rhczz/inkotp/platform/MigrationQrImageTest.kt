package io.github.rhczz.inkotp.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MigrationQrImageTest {
    @Test fun savedPngContainsExactMigrationUri() {
        // Public test data, no account seed.
        val publicPage = "otpauth-migration://offline?data=EAEYAQ%3D%3D"
        assertEquals(publicPage, decodeQrImage(encodeMigrationQrPng(publicPage.encodeToByteArray())))
    }
}
