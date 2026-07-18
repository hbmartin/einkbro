package info.plateaukao.einkbro.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Preference keys whose values are secrets (API keys, passwords, OAuth material).
 * They live in [SecretStore] instead of the plaintext default SharedPreferences.
 */
object SecretKeys {
    /** Secrets a user may carry to another device; exported to backups only
     *  inside the passphrase-encrypted `secrets.enc` entry. */
    val BACKUP: List<String> = listOf(
        AiConfig.K_GPT_API_KEY,
        AiConfig.K_GEMINI_API_KEY,
        AiConfig.K_IMAGE_API_KEY,
        ConfigManager.K_INSTAPAPER_USERNAME,
        ConfigManager.K_INSTAPAPER_PASSWORD,
    )

    /** Everything stored encrypted at rest. */
    val ALL: List<String> = BACKUP

    /** Device OAuth state used by older versions. It may contain a refresh token,
     *  so it is deleted instead of migrated or backed up. */
    val DEPRECATED_DEVICE_OAUTH: List<String> = listOf(
        "sp_drive_auth_state",
        "sp_drive_pending_auth",
    )
}

/** Synchronous facade over the encrypted secret storage, mirroring the subset of
 *  SharedPreferences that the preference delegates need. */
interface SecretPrefs {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun putAll(values: Map<String, String>)

    /** Current non-empty values for [keys]. */
    fun snapshot(keys: Collection<String>): Map<String, String>

    /** Suspends until the store is loaded and any plaintext migration has run. */
    suspend fun ensureReady()
}

private val Context.secretsDataStore by preferencesDataStore(name = "secrets")

class SecretStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal interface SecretPersistence {
    fun read(): Map<String, String>
    fun update(values: Map<String, String?>)
}

private class DataStoreSecretPersistence(
    private val context: Context,
) : SecretPersistence {
    override fun read(): Map<String, String> = runBlocking {
        context.secretsDataStore.data.first().asMap().mapNotNull { (key, value) ->
            (value as? String)?.let { key.name to it }
        }.toMap()
    }

    override fun update(values: Map<String, String?>) {
        runBlocking {
            context.secretsDataStore.edit { prefs ->
                values.forEach { (key, value) ->
                    val prefKey = stringPreferencesKey(key)
                    if (value == null) prefs.remove(prefKey) else prefs[prefKey] = value
                }
            }
        }
    }
}

/**
 * Stores secrets encrypted at rest: each value is AES-GCM-encrypted with a Tink
 * [Aead] whose keyset is wrapped by an Android Keystore master key, then kept in
 * a Preferences DataStore (files/datastore/secrets.preferences_pb) — outside the
 * shared_prefs/ directory that "export all preferences" copies verbatim.
 *
 * Reads are served from an in-memory cache loaded once (and primed off the main
 * thread from Application.onCreate); writes are rare (settings edits, sign-ins,
 * restore) and persist synchronously.
 *
 * A write fails explicitly when the Keystore-backed Aead is unavailable. Existing
 * legacy `p:` values are accepted only long enough to re-encrypt them during load;
 * the store is not reported ready unless that rewrite succeeds.
 */
class SecretStore internal constructor(
    private val plainPrefs: SharedPreferences,
    private val persistence: SecretPersistence,
    private val aeadFactory: () -> Aead,
) : SecretPrefs {

    constructor(context: Context, plainPrefs: SharedPreferences) : this(
        plainPrefs = plainPrefs,
        persistence = DataStoreSecretPersistence(context),
        aeadFactory = { createAead(context) },
    )

    private var cache: Map<String, String>? = null
    private val lock = Any()

    private val aead: Aead by lazy {
        try {
            aeadFactory()
        } catch (e: Exception) {
            throw SecretStorageException("Encrypted secret storage is unavailable", e)
        }
    }

    /** Loads the cache and migrates any plaintext secrets out of the default
     *  SharedPreferences. Called from a background coroutine at app start so the
     *  first on-demand access rarely pays the Keystore/DataStore cost. */
    suspend fun prime() {
        ensureReady()
    }

    override suspend fun ensureReady() {
        withContext(Dispatchers.IO) { ensureLoaded() }
    }

    override fun getString(key: String, defaultValue: String): String =
        synchronized(lock) {
            ensureLoadedLocked()[key] ?: defaultValue
        }

    override fun putString(key: String, value: String) = putAll(mapOf(key to value))

    override fun putAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        synchronized(lock) {
            val loaded = ensureLoadedLocked()
            val encoded = values.mapValues { (key, value) ->
                value.takeIf { it.isNotEmpty() }?.let { encode(key, it) }
            }
            try {
                persistence.update(encoded)
            } catch (e: Exception) {
                if (e is SecretStorageException) throw e
                throw SecretStorageException("Failed to persist encrypted secrets", e)
            }
            cache = loaded.toMutableMap().apply {
                values.forEach { (key, value) ->
                    if (value.isEmpty()) remove(key) else this[key] = value
                }
            }.toMap()
        }
    }

    override fun snapshot(keys: Collection<String>): Map<String, String> =
        synchronized(lock) {
            val loaded = ensureLoadedLocked()
            keys.mapNotNull { key -> loaded[key]?.let { key to it } }.toMap()
        }

    private fun ensureLoaded(): Map<String, String> =
        synchronized(lock) { ensureLoadedLocked() }

    private fun ensureLoadedLocked(): Map<String, String> {
        cache?.let { return it }
        try {
            val loaded = mutableMapOf<String, String>()
            val plaintextValues = mutableMapOf<String, String>()
            persistence.read().forEach { (key, stored) ->
                decode(key, stored)
                    .takeIf { it.isNotEmpty() }
                    ?.let { value ->
                        loaded[key] = value
                        if (stored.startsWith(PLAIN_PREFIX)) plaintextValues[key] = value
                    }
            }
            cache = loaded.toMap()
            if (plaintextValues.isNotEmpty()) {
                putAll(plaintextValues)
            }
            // Sweep on every first load: this also captures plaintext values that
            // reappear in the default prefs when an old backup zip is restored
            // (raw file copy + app restart).
            SecretMigration.sweep(plainPrefs, this)
            return checkNotNull(cache)
        } catch (e: Exception) {
            cache = null
            if (e is SecretStorageException) throw e
            throw SecretStorageException("Failed to load encrypted secrets", e)
        }
    }

    // Self-describing value encoding. The legacy "p:" form is read only for
    // immediate migration; all new persistence must use "enc1:".
    private fun encode(key: String, value: String): String {
        return try {
            ENC_PREFIX + aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
                .toByteString().base64()
        } catch (e: Exception) {
            if (e is SecretStorageException) throw e
            throw SecretStorageException("Failed to encrypt secret", e)
        }
    }

    private fun decode(key: String, stored: String): String = try {
        when {
            stored.startsWith(ENC_PREFIX) -> {
                val cipherBytes = stored.removePrefix(ENC_PREFIX).decodeBase64()?.toByteArray()
                    ?: throw SecretStorageException("Invalid encrypted secret encoding")
                aead.decrypt(cipherBytes, key.toByteArray(Charsets.UTF_8))
                    .toString(Charsets.UTF_8)
            }

            stored.startsWith(PLAIN_PREFIX) -> stored.removePrefix(PLAIN_PREFIX)
            else -> throw SecretStorageException("Unknown secret encoding")
        }
    } catch (e: Exception) {
        if (e is SecretStorageException) throw e
        throw SecretStorageException("Failed to decrypt secret", e)
    }

    companion object {
        // The Tink keyset lives in its own SharedPreferences file. It is wrapped by
        // the Keystore master key, but BackupUnit must still exclude it from export
        // (useless off-device, and restoring a foreign keyset would clobber the
        // local one) — see KEYSET_PREF_XML.
        private const val KEYSET_NAME = "einkbro_secret_keyset"
        private const val KEYSET_PREF_FILE = "einkbro_secret_keyset_prefs"
        const val KEYSET_PREF_XML = "$KEYSET_PREF_FILE.xml"
        private const val MASTER_KEY_URI = "android-keystore://einkbro_secret_master_key"

        private const val ENC_PREFIX = "enc1:"
        private const val PLAIN_PREFIX = "p:"

        private fun createAead(context: Context): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }
    }
}

object SecretMigration {
    /**
     * Moves any plaintext secret values from the default SharedPreferences into
     * [secrets], then deletes the plaintext copies. Idempotent; runs on every
     * first load of the store so secrets restored from old backups (which land
     * as plaintext in the default prefs) are swept on the next launch.
     *
     * Obsolete device OAuth state is purged from both stores rather than
     * migrated because it may contain a long-lived refresh token.
     *
     * @return true when anything was migrated or deleted.
     */
    fun sweep(sp: SharedPreferences, secrets: SecretPrefs): Boolean {
        val found = SecretKeys.ALL.mapNotNull { key ->
            sp.getString(key, null)?.takeIf { it.isNotEmpty() }?.let { key to it }
        }
        if (found.isNotEmpty()) {
            secrets.putAll(found.toMap())
        }

        val deprecatedEncrypted = secrets.snapshot(SecretKeys.DEPRECATED_DEVICE_OAUTH).keys
        if (deprecatedEncrypted.isNotEmpty()) {
            secrets.putAll(deprecatedEncrypted.associateWith { "" })
        }

        val plaintextKeysToDelete = (SecretKeys.ALL + SecretKeys.DEPRECATED_DEVICE_OAUTH)
            .filter(sp::contains)
        if (plaintextKeysToDelete.isEmpty() && deprecatedEncrypted.isEmpty()) {
            return false
        }
        // Commit (not apply) and verify the result so callers never treat the
        // store as ready while the on-disk XML still contains plaintext.
        val editor = sp.edit()
        plaintextKeysToDelete.forEach(editor::remove)
        if (!editor.commit()) {
            throw SecretStorageException("Failed to delete plaintext secrets")
        }
        return true
    }
}
