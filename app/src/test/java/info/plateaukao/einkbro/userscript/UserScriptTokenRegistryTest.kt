package info.plateaukao.einkbro.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptTokenRegistryTest {

    @Test
    fun `issueToken is stable for the same script within one document`() {
        val registry = UserScriptTokenRegistry()
        val first = registry.issueToken(1L)
        val second = registry.issueToken(1L)
        assertEquals(first, second)
    }

    @Test
    fun `distinct scripts get distinct tokens`() {
        val registry = UserScriptTokenRegistry()
        assertNotEquals(registry.issueToken(1L), registry.issueToken(2L))
    }

    @Test
    fun `resolve maps an issued token back to its script id`() {
        val registry = UserScriptTokenRegistry()
        val token = registry.issueToken(42L)
        assertEquals(42L, registry.resolve(token))
        assertTrue(registry.isValid(token))
    }

    @Test
    fun `resolve denies unknown null and guessed-id tokens`() {
        val registry = UserScriptTokenRegistry()
        registry.issueToken(1L)
        assertNull(registry.resolve(null))
        assertNull(registry.resolve(""))
        // A page guessing the sequential script id (the pre-token attack) is denied.
        assertNull(registry.resolve("1"))
        assertNull(registry.resolve("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `clear invalidates old tokens and the next issue differs`() {
        val registry = UserScriptTokenRegistry()
        val old = registry.issueToken(1L)
        registry.clear()
        assertNull(registry.resolve(old))
        assertNotEquals(old, registry.issueToken(1L))
    }

    @Test
    fun `default generator produces unguessable unique uuids`() {
        val registry = UserScriptTokenRegistry()
        val tokens = (1L..50L).map { registry.issueToken(it) }
        assertEquals(tokens.size, tokens.toSet().size)
        tokens.forEach { assertEquals(36, it.length) }
    }

    @Test
    fun `injected generator is used`() {
        var count = 0
        val registry = UserScriptTokenRegistry { "token-${count++}" }
        assertEquals("token-0", registry.issueToken(7L))
        assertEquals(7L, registry.resolve("token-0"))
    }
}
