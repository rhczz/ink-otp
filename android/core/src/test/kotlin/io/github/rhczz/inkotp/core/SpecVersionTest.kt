package io.github.rhczz.inkotp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecVersionTest {

    @Test
    fun specVersionStartsAtOne() {
        assertEquals(1, CORE_SPEC_VERSION)
    }
}
