package info.plateaukao.einkbro.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import timber.log.Timber

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

    /** Everything stored encrypted at rest. The Google Drive OAuth entries are
     *  never exported at all: a refresh token grants live access to the user's
     *  Drive, and signing in again on a new device is cheap. */
    val ALL: List<String> = BACKUP + listOf(
        ConfigManager.K_DRIVE_AUTH_STATE,
        ConfigManager.K_DRIVE_PENDING_AUTH,
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

    /** Blocks until the store is loaded and any plaintext migration has run. */
    fun ensureReady()
}

private val Context.secretsDataStore by preferencesDataStore(name = "secrets")

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
 * Devices with a broken Keystore must not crash or lose data: when the Aead is
 * unavailable, values fall back to a plaintext encoding inside the DataStore
 * file — the same exposure as the old plaintext SharedPreferences, still outside
 * any backup. Each stored value is prefixed with its encoding so a later repaired
 * Keystore re-encrypts transparently on the next write.
 */
class SecretStore(
    private val context: Context,
    private val plainPrefs: SharedPreferences,
) : SecretPrefs {

    @Volatile
    private var cache: MutableMap<String, String>? = null
    private val lock = Any()

    private val aead: Aead? by lazy {
        try {
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        } catch (t: Throwable) {
            Timber.e(t, "Keystore/Tink unavailable; storing secrets without encryption")
            null
        }
    }

    /** Loads the cache and migrates any plaintext secrets out of the default
     *  SharedPreferences. Called from a background coroutine at app start so the
     *  first on-demand access rarely pays the Keystore/DataStore cost. */
    fun prime() {
        runCatching { ensureLoaded() }
    }

    override fun ensureReady() {
        ensureLoaded()
    }

    override fun getString(key: String, defaultValue: String): String =
        ensureLoaded()[key] ?: defaultValue

    override fun putString(key: String, value: String) = putAll(mapOf(key to value))

    override fun putAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        synchronized(lock) {
            val loaded = ensureLoaded()
            values.forEach { (key, value) ->
                if (value.isEmpty()) loaded.remove(key) else loaded[key] = value
            }
            runBlocking {
                context.secretsDataStore.edit { prefs ->
                    values.forEach { (key, value) ->
                        if (value.isEmpty()) prefs.remove(stringPreferencesKey(key))
                        else prefs[stringPreferencesKey(key)] = encode(key, value)
                    }
                }
            }
        }
    }

    override fun snapshot(keys: Collection<String>): Map<String, String> {
        val loaded = ensureLoaded()
        return keys.mapNotNull { key -> loaded[key]?.let { key to it } }.toMap()
    }

    private fun ensureLoaded(): MutableMap<String, String> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = mutableMapOf<String, String>()
            runCatching {
                runBlocking { context.secretsDataStore.data.first() }.asMap()
                    .forEach { (prefKey, stored) ->
                        if (stored is String) {
                            decode(prefKey.name, stored)
                                .takeIf { it.isNotEmpty() }
                                ?.let { loaded[prefKey.name] = it }
                        }
                    }
            }.onFailure { Timber.e(it, "Failed to load secret store") }
            cache = loaded
            // Sweep on every first load: this also captures plaintext values that
            // reappear in the default prefs when an old backup zip is restored
            // (raw file copy + app restart).
            runCatching { SecretMigration.sweep(plainPrefs, this) }
                .onFailure { Timber.e(it, "Secret migration failed") }
            return loaded
        }
    }

    // Self-describing value encoding: "enc1:" + base64(AEAD ciphertext bound to
    // the pref key) when encryption works, "p:" + plaintext when it doesn't.
    private fun encode(key: String, value: String): String {
        val currentAead = aead ?: return PLAIN_PREFIX + value
        return runCatching {
            ENC_PREFIX + currentAead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
                .toByteString().base64()
        }.getOrElse {
            Timber.e(it, "Encrypt failed; storing secret without encryption")
            PLAIN_PREFIX + value
        }
    }

    private fun decode(key: String, stored: String): String = when {
        stored.startsWith(ENC_PREFIX) -> runCatching {
            val cipherBytes = stored.removePrefix(ENC_PREFIX).decodeBase64()?.toByteArray()
                ?: return ""
            aead?.decrypt(cipherBytes, key.toByteArray(Charsets.UTF_8))
                ?.toString(Charsets.UTF_8)
                .orEmpty()
        }.getOrElse {
            // Foreign or lost keyset (e.g. the DataStore file was copied from
            // another device): treat as unset rather than crashing.
            Timber.e(it, "Decrypt failed for secret $key")
            ""
        }

        stored.startsWith(PLAIN_PREFIX) -> stored.removePrefix(PLAIN_PREFIX)
        else -> ""
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
    }
}

object SecretMigration {
    /**
     * Moves any plaintext secret values from the default SharedPreferences into
     * [secrets], then deletes the plaintext copies. Idempotent; runs on every
     * first load of the store so secrets restored from old backups (which land
     * as plaintext in the default prefs) are swept on the next launch.
     *
     * @return true when something was migrated.
     */
    fun sweep(sp: SharedPreferences, secrets: SecretPrefs): Boolean {
        val found = SecretKeys.ALL.mapNotNull { key ->
            sp.getString(key, null)?.takeIf { it.isNotEmpty() }?.let { key to it }
        }
        if (found.isEmpty()) return false
        secrets.putAll(found.toMap())
        // commit (not apply) so the on-disk XML loses the plaintext before any
        // "export all preferences" could copy the file verbatim.
        sp.edit(commit = true) {
            found.forEach { (key, _) -> remove(key) }
        }
        return true
    }
}
