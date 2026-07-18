package info.plateaukao.einkbro.unit

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for the `secrets.enc` backup entry: a string map is
 * serialized to JSON and sealed with AES-256-GCM under a key derived from the
 * user's passphrase.
 *
 * KDF is PBKDF2WithHmacSHA1 because it is the strongest PBKDF2 variant available
 * on every supported device (SecretKeyFactory "PBKDF2WithHmacSHA256" needs
 * API 26, minSdk is 24) and a backup written on a new device must restore on an
 * old one. The envelope records kdf and iteration count, so both can be raised
 * later without breaking the ability to restore existing backups.
 */
class PassphraseCipher(private val iterations: Int = DEFAULT_ITERATIONS) {

    fun encrypt(payload: Map<String, String>, passphrase: CharArray): JSONObject {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(AAD)
        }
        val plain = JSONObject().apply { payload.forEach { (key, value) -> put(key, value) } }
        val cipherText = cipher.doFinal(plain.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", VERSION)
            .put("kdf", KDF)
            .put("iterations", iterations)
            .put("salt", salt.toByteString().base64())
            .put("iv", iv.toByteString().base64())
            .put("data", cipherText.toByteString().base64())
    }

    /** @return the decrypted map, or null on a wrong passphrase, tampered data,
     *  or an envelope this version cannot read. */
    fun decrypt(envelope: JSONObject, passphrase: CharArray): Map<String, String>? {
        if (envelope.optInt("version", -1) != VERSION) return null
        if (envelope.optString("kdf") != KDF) return null
        return try {
            val salt = envelope.getString("salt").decodeBase64()?.toByteArray() ?: return null
            val iv = envelope.getString("iv").decodeBase64()?.toByteArray() ?: return null
            val data = envelope.getString("data").decodeBase64()?.toByteArray() ?: return null
            if (salt.size != SALT_LENGTH || iv.size != IV_LENGTH) return null
            if (data.size !in MIN_CIPHERTEXT_LENGTH..MAX_CIPHERTEXT_LENGTH) return null
            val envelopeIterations = envelope.optInt("iterations", -1)
            if (envelopeIterations !in MIN_SUPPORTED_ITERATIONS..MAX_SUPPORTED_ITERATIONS) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, envelopeIterations), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(AAD)
            }
            val json = JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterationCount: Int): SecretKeySpec {
        val keySpec = PBEKeySpec(passphrase, salt, iterationCount, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(KDF).generateSecret(keySpec).encoded
        keySpec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        const val KDF = "PBKDF2WithHmacSHA1"
        private const val VERSION = 1

        // Balances brute-force cost against derivation time on slow e-ink SoCs
        // (backups are an explicit user action, so ~a second is acceptable).
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_SUPPORTED_ITERATIONS = 100_000
        const val MAX_SUPPORTED_ITERATIONS = 1_200_000

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val MIN_CIPHERTEXT_LENGTH = TAG_BITS / 8
        private const val MAX_CIPHERTEXT_LENGTH = 256 * 1024
        private val AAD = "einkbro-secrets:1".toByteArray(Charsets.UTF_8)
    }
}
