package io.github.rhczz.inkotp.core.crypto

import java.security.SecureRandom

fun interface RandomSource {
    fun bytes(size: Int): ByteArray
}

class SecureRandomSource : RandomSource {
    private val random = SecureRandom()

    override fun bytes(size: Int): ByteArray {
        require(size > 0) { "size must be positive" }
        return ByteArray(size).also { random.nextBytes(it) }
    }
}
