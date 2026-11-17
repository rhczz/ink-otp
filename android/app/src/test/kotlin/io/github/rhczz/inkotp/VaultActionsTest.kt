package io.github.rhczz.inkotp

import io.github.rhczz.inkotp.app.VaultActions
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import org.junit.Assert.*
import org.junit.Test

class VaultActionsTest {
    private val empty = VaultModel(vaultId = "00000000-0000-4000-8000-000000000001", createdAt = "2026-11-17T00:00:00Z", updatedAt = "2026-11-17T00:00:00Z")

    @Test fun `manual entry keeps eight digit sha256 uri parameters`() {
        val added = VaultActions.add(empty, "", "", "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60")
        val item = added.items.single()
        assertEquals("Example", item.issuer)
        assertEquals("user", item.accountName)
        assertEquals(OtpAlgorithm.SHA256, item.algorithm)
        assertEquals(8, item.digits)
        assertEquals(60, item.period)
    }

    @Test fun `blank account and invalid secret cannot create an entry`() {
        assertThrows(IllegalArgumentException::class.java) { VaultActions.add(empty, "", "", "JBSWY3DPEHPK3PXP") }
        assertThrows(IllegalArgumentException::class.java) { VaultActions.add(empty, "test", "user", "not!a!key") }
        assertTrue(empty.items.isEmpty())
    }

    @Test fun `duplicate entry is rejected without changing the vault`() {
        val one = VaultActions.add(empty, "Example", "user", "JBSWY3DPEHPK3PXP")
        assertThrows(IllegalArgumentException::class.java) { VaultActions.add(one, "example", "USER", "JBSWY3DPEHPK3PXP") }
        assertEquals(1, one.items.size)
    }

    @Test fun `real code changes across a period boundary`() {
        val vault = VaultActions.add(empty, "Example", "user", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertEquals("287082", VaultActions.project(vault, 59).single().code)
        assertEquals(1, VaultActions.project(vault, 59).single().remainingSeconds)
        assertEquals(30, VaultActions.project(vault, 60).single().remainingSeconds)
        assertNotEquals(VaultActions.project(vault, 59).single().code, VaultActions.project(vault, 60).single().code)
    }

    @Test fun `edit and ordering preserve secret and stable identifiers`() {
        val one = VaultActions.add(empty, "A", "first", "JBSWY3DPEHPK3PXP")
        val two = VaultActions.add(one, "B", "second", "MZXW6YTB")
        val edited = VaultActions.edit(two, one.items.first().id, "New", "renamed")
        assertArrayEquals(one.items.first().secret, edited.items.first().secret)
        val moved = VaultActions.move(edited, edited.items.last().id, -1)
        assertEquals("second", moved.items.first().accountName)
        assertEquals(two.items.map { it.id }.toSet(), moved.items.map { it.id }.toSet())
        assertEquals(1, VaultActions.delete(moved, one.items.first().id).items.size)
    }

    @Test fun `password policy blocks numeric common and short passwords`() {
        for (password in listOf("12345678", "password", "short")) assertNotNull(VaultActions.passwordError(password))
        assertNull(VaultActions.passwordError("My recovery phrase 2026"))
    }

    @Test fun `unsupported migration and hotp do not enter the totp workflow`() {
        assertThrows(IllegalArgumentException::class.java) { VaultActions.add(empty, "", "", "otpauth-migration://offline?data=AAAA") }
        assertThrows(IllegalArgumentException::class.java) { VaultActions.add(empty, "", "", "otpauth://hotp/A:b?secret=MZXW6YTB&counter=1") }
    }
}
