package info.plateaukao.einkbro.preference

import com.google.crypto.tink.Aead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    private val plainPrefs = FakeSharedPreferences()

    @Test
    fun `failed initial read remains retryable`() {
        val persistence = FakeSecretPersistence(
            mutableMapOf("key" to "p:value"),
        ).apply {
            failedReadsRemaining = 1
        }
        val store = SecretStore(plainPrefs, persistence, ::FakeAead)

        assertThrows(SecretStorageException::class.java) {
            store.getString("key", "")
        }

        assertEquals("value", store.getString("key", ""))
        assertTrue(persistence.values.getValue("key").startsWith("enc1:"))
    }

    @Test
    fun `failed plaintext cleanup prevents readiness and remains retryable`() {
        plainPrefs.store[AiConfig.K_GPT_API_KEY] = "legacy-secret"
        plainPrefs.commitSucceeds = false
        val persistence = FakeSecretPersistence()
        val store = SecretStore(plainPrefs, persistence, ::FakeAead)

        assertThrows(SecretStorageException::class.java) {
            store.getString(AiConfig.K_GPT_API_KEY, "")
        }
        assertTrue(plainPrefs.contains(AiConfig.K_GPT_API_KEY))

        plainPrefs.commitSucceeds = true
        assertEquals("legacy-secret", store.getString(AiConfig.K_GPT_API_KEY, ""))
        assertFalse(plainPrefs.contains(AiConfig.K_GPT_API_KEY))
    }

    @Test
    fun `failed durable write is not published to the cache`() {
        val persistence = FakeSecretPersistence()
        val store = SecretStore(plainPrefs, persistence, ::FakeAead)
        assertEquals("", store.getString("key", ""))
        persistence.failWrites = true

        assertThrows(SecretStorageException::class.java) {
            store.putString("key", "new-value")
        }

        assertEquals("", store.getString("key", ""))
        assertFalse(persistence.values.containsKey("key"))
    }

    @Test
    fun `unavailable encryption fails without persisting plaintext`() {
        val persistence = FakeSecretPersistence()
        val store = SecretStore(plainPrefs, persistence) {
            throw IllegalStateException("Keystore unavailable")
        }
        assertEquals("", store.getString("key", ""))

        assertThrows(SecretStorageException::class.java) {
            store.putString("key", "secret")
        }

        assertFalse(persistence.values.containsKey("key"))
    }

    @Test
    fun `successful writes are encrypted before publication`() {
        val persistence = FakeSecretPersistence()
        val store = SecretStore(plainPrefs, persistence, ::FakeAead)

        store.putString("key", "secret")

        assertEquals("secret", store.getString("key", ""))
        assertTrue(persistence.values.getValue("key").startsWith("enc1:"))
        assertFalse(persistence.values.getValue("key").contains("secret"))
    }
}

private class FakeSecretPersistence(
    val values: MutableMap<String, String> = mutableMapOf(),
) : SecretPersistence {
    var failedReadsRemaining: Int = 0
    var failWrites: Boolean = false

    override fun read(): Map<String, String> {
        if (failedReadsRemaining > 0) {
            failedReadsRemaining--
            throw IllegalStateException("read failed")
        }
        return values.toMap()
    }

    override fun update(values: Map<String, String?>) {
        if (failWrites) throw IllegalStateException("write failed")
        values.forEach { (key, value) ->
            if (value == null) this.values.remove(key) else this.values[key] = value
        }
    }
}

private class FakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        ByteArray(plaintext.size + 1).also { output ->
            output[0] = MARKER
            plaintext.forEachIndexed { index, byte ->
                output[index + 1] = (byte.toInt() xor MASK).toByte()
            }
        }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.firstOrNull() == MARKER)
        return ByteArray(ciphertext.size - 1) { index ->
            (ciphertext[index + 1].toInt() xor MASK).toByte()
        }
    }

    private companion object {
        const val MARKER: Byte = 0x42
        const val MASK: Int = 0x5A
    }
}
