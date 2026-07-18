package info.plateaukao.einkbro.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassphraseCipherTest {
    private val cipher = PassphraseCipher()
    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `supported envelope survives a round trip`() {
        val payload = mapOf("api-key" to "secret")
        val envelope = cipher.encrypt(payload, passphrase)

        assertEquals(payload, cipher.decrypt(envelope, passphrase))
    }

    @Test
    fun `unsupported envelope version is rejected`() {
        val envelope = cipher.encrypt(mapOf("api-key" to "secret"), passphrase)
            .put("version", 2)

        assertNull(cipher.decrypt(envelope, passphrase))
    }

    @Test
    fun `invalid iteration count is rejected before key derivation`() {
        val envelope = cipher.encrypt(mapOf("api-key" to "secret"), passphrase)
            .put("iterations", Int.MAX_VALUE)

        assertNull(cipher.decrypt(envelope, passphrase))
    }
}
