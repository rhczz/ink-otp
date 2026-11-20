package io.github.rhczz.inkotp.core.interchange

import org.junit.jupiter.api.Test
import java.io.File

class SwiftContractTest {
    @Test
    fun swiftGeneratedBackupsOpenWithAndroidProductionCodecs() {
        val directory = System.getenv("INKOTP_IOS_CONTRACT_DIR")?.let(::File) ?: return
        val expected = PublicInterchangeContract.publicExample()
        PublicInterchangeContract.assertSwiftExports(directory, expected, platform = "iOS")
    }
}
