package info.plateaukao.einkbro.unit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.database.Article
import info.plateaukao.einkbro.database.Bookmark
import info.plateaukao.einkbro.database.BookmarkManager
import info.plateaukao.einkbro.database.ChatGptQuery
import info.plateaukao.einkbro.database.CookieDomain
import info.plateaukao.einkbro.database.DomainConfiguration
import info.plateaukao.einkbro.database.FaviconInfo
import info.plateaukao.einkbro.database.Highlight
import info.plateaukao.einkbro.database.JavascriptDomain
import info.plateaukao.einkbro.database.Record
import info.plateaukao.einkbro.database.RecordRepository
import info.plateaukao.einkbro.database.SavedPage
import info.plateaukao.einkbro.database.WhitelistDomain
import info.plateaukao.einkbro.preference.SecretKeys
import info.plateaukao.einkbro.preference.SecretPrefs
import info.plateaukao.einkbro.preference.SecretStore
import info.plateaukao.einkbro.view.EBToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class BackupCategory(val displayNameResId: Int) {
    ALL_PREFERENCES(R.string.backup_category_all_preferences),
    GPT_SETTINGS(R.string.backup_category_gpt_settings),
    BOOKMARKS(R.string.backup_category_bookmarks),
    HISTORY(R.string.backup_category_history),
    DATABASE_DATA(R.string.backup_category_database_data),

    // API keys and passwords. Only ever written as the passphrase-encrypted
    // secrets.enc entry; silently ignored by older app versions restoring a
    // newer zip (unknown category names and entries are skipped).
    SECRETS(R.string.backup_category_secrets),
}


class BackupUnit(
    private val context: Context,
) : KoinComponent {
    private val bookmarkManager: BookmarkManager by inject()
    private val recordDb: RecordRepository by inject()
    private val sp: SharedPreferences by inject()
    private val secretPrefs: SecretPrefs by inject()
    private val coroutineScope: CoroutineScope by inject()

    // Per-app data directories derived from the running app's own context, so backup
    // and restore touch *this* app's sandbox rather than a hardcoded package path.
    // Required for non-default applicationIds (e.g. the `.a` side-by-side build),
    // whose data lives under /data/data/info.plateaukao.einkbro.a/.
    private val sharedPrefsDir: File get() = File(context.dataDir, "shared_prefs")
    private val databasesDir: File get() = File(context.dataDir, "databases")

    suspend fun backupData(
        context: Context,
        uri: Uri,
        categories: Set<BackupCategory>,
        secretsPassphrase: String? = null,
    ): Boolean {
        return try {
            val written = withContext(Dispatchers.IO) {
                val fos = context.contentResolver.openOutputStream(uri) ?: return@withContext false
                writeBackupZip(fos, categories, secretsPassphrase)
                true
            }
            if (!written) return false
            EBToast.show(context, R.string.toast_backup_successful)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            EBToast.show(context, R.string.toast_error)
            false
        }
    }

    suspend fun backupToTempFile(
        categories: Set<BackupCategory>,
        fileName: String = "backup_share.zip",
        secretsPassphrase: String? = null,
    ): File? {
        val tempFile = File(context.cacheDir, fileName)
        return try {
            withContext(Dispatchers.IO) {
                writeBackupZip(FileOutputStream(tempFile), categories, secretsPassphrase)
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            null
        }
    }

    private suspend fun writeBackupZip(
        outputStream: java.io.OutputStream,
        categories: Set<BackupCategory>,
        secretsPassphrase: String? = null,
    ) {
        // Secrets are only exported passphrase-encrypted; without a passphrase the
        // category is dropped (also from the manifest, so a restore never offers a
        // category the zip doesn't contain).
        val effectiveCategories =
            if (secretsPassphrase == null) categories - BackupCategory.SECRETS else categories

        val zos = ZipOutputStream(outputStream)

        // Write manifest
        val manifest = JSONObject().apply {
            put("version", 2)
            put("categories", JSONArray(effectiveCategories.map { it.name }))
        }
        zos.putNextEntry(ZipEntry(MANIFEST_FILE))
        zos.write(manifest.toString().toByteArray())
        zos.closeEntry()

        if (BackupCategory.ALL_PREFERENCES in effectiveCategories) {
            // Make sure the plaintext→encrypted migration has swept the default
            // prefs XML before it is copied verbatim (the sweep normally runs at
            // app start, but don't race it).
            secretPrefs.ensureReady()
            val sharedPrefsDirectory = sharedPrefsDir
            val sharedPrefsFiles = sharedPrefsDirectory.listFiles()
            if (sharedPrefsFiles != null) {
                for (sharedPrefsFile in sharedPrefsFiles) {
                    // The Tink keyset never leaves the device: it is useless without
                    // this device's Keystore master key, and restoring a foreign
                    // keyset would clobber the local one.
                    if (sharedPrefsFile.name == SecretStore.KEYSET_PREF_XML) continue
                    writeFileToZip(zos, sharedPrefsFile, "shared_prefs/${sharedPrefsFile.name}")
                }
            }
        }

        if (BackupCategory.SECRETS in effectiveCategories && secretsPassphrase != null) {
            val payload = secretPrefs.snapshot(SecretKeys.BACKUP)
            val envelope = withContext(Dispatchers.Default) {
                PassphraseCipher().encrypt(payload, secretsPassphrase.toCharArray())
            }
            zos.putNextEntry(ZipEntry(SECRETS_FILE))
            zos.write(envelope.toString().toByteArray())
            zos.closeEntry()
        }

        if (BackupCategory.GPT_SETTINGS in effectiveCategories) {
            val gptJson = exportGptSettings()
            zos.putNextEntry(ZipEntry(GPT_SETTINGS_FILE))
            zos.write(gptJson.toString().toByteArray())
            zos.closeEntry()
        }

        if (BackupCategory.BOOKMARKS in effectiveCategories) {
            val bookmarks = kotlinx.coroutines.runBlocking {
                bookmarkManager.getAllBookmarks()
            }
            zos.putNextEntry(ZipEntry(BOOKMARKS_FILE))
            zos.write(bookmarks.toJsonString().toByteArray())
            zos.closeEntry()
        }

        if (BackupCategory.HISTORY in effectiveCategories) {
            val history = recordDb.listAllHistory()
            val jsonArray = JSONArray()
            for (record in history) {
                jsonArray.put(JSONObject().apply {
                    put("title", record.title)
                    put("url", record.url)
                    put("time", record.time)
                })
            }
            zos.putNextEntry(ZipEntry(HISTORY_FILE))
            zos.write(jsonArray.toString().toByteArray())
            zos.closeEntry()
        }

        if (BackupCategory.DATABASE_DATA in effectiveCategories) {
            val db = bookmarkManager.database
            val json = JSONObject()

            // Favicons
            val favicons = JSONArray()
            for (f in db.faviconDao().getAllFavicons()) {
                favicons.put(JSONObject().apply {
                    put("domain", f.domain)
                    put("icon", f.icon?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                })
            }
            json.put("favicons", favicons)

            // Articles & Highlights (articles first since highlights reference them)
            val articles = JSONArray()
            for (a in db.articleDao().getAllArticlesAsync()) {
                articles.put(JSONObject().apply {
                    put("id", a.id)
                    put("title", a.title)
                    put("url", a.url)
                    put("date", a.date)
                    put("tags", a.tags)
                })
            }
            json.put("articles", articles)

            val highlights = JSONArray()
            for (h in db.highlightDao().getAllHighlightsAsync()) {
                highlights.put(JSONObject().apply {
                    put("id", h.id)
                    put("articleId", h.articleId)
                    put("content", h.content)
                })
            }
            json.put("highlights", highlights)

            // ChatGptQuery
            val queries = JSONArray()
            for (q in db.chatGptQueryDao().getAllChatGptQueriesAsync()) {
                queries.put(JSONObject().apply {
                    put("id", q.id)
                    put("date", q.date)
                    put("url", q.url)
                    put("model", q.model)
                    put("selectedText", q.selectedText)
                    put("result", q.result)
                })
            }
            json.put("chat_gpt_queries", queries)

            // DomainConfiguration
            val domainConfigs = JSONArray()
            for (dc in db.domainConfigurationDao().getAllDomainConfigurations()) {
                domainConfigs.put(JSONObject().apply {
                    put("domain", dc.domain)
                    put("configuration", dc.configuration)
                })
            }
            json.put("domain_configurations", domainConfigs)

            // SavedPage
            val savedPages = JSONArray()
            for (sp in db.savedPageDao().getAllSavedPagesAsync()) {
                savedPages.put(JSONObject().apply {
                    put("id", sp.id)
                    put("title", sp.title)
                    put("url", sp.url)
                    put("filePath", sp.filePath)
                    put("savedAt", sp.savedAt)
                })
            }
            json.put("saved_pages", savedPages)

            // Domain lists
            val whitelistDomains = JSONArray()
            for (d in db.domainListDao().getAllWhitelistDomains()) {
                whitelistDomains.put(d)
            }
            json.put("whitelist_domains", whitelistDomains)

            val javascriptDomains = JSONArray()
            for (d in db.domainListDao().getAllJavascriptDomains()) {
                javascriptDomains.put(d)
            }
            json.put("javascript_domains", javascriptDomains)

            val cookieDomains = JSONArray()
            for (d in db.domainListDao().getAllCookieDomains()) {
                cookieDomains.put(d)
            }
            json.put("cookie_domains", cookieDomains)

            zos.putNextEntry(ZipEntry(DATABASE_DATA_FILE))
            zos.write(json.toString().toByteArray())
            zos.closeEntry()
        }

        zos.close()
        outputStream.close()
    }

    /**
     * Returns available categories in the zip, or null if it's a legacy backup format.
     */
    fun getAvailableCategories(context: Context, uri: Uri): Set<BackupCategory>? {
        try {
            val fis = context.contentResolver.openInputStream(uri) ?: return null
            val zis = ZipInputStream(fis)
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name == MANIFEST_FILE) {
                    val content = readEntryBytes(zis, MAX_MANIFEST_BYTES)
                    val manifest = JSONObject(String(content))
                    val categoriesArray = manifest.getJSONArray("categories")
                    val categories = mutableSetOf<BackupCategory>()
                    for (i in 0 until categoriesArray.length()) {
                        try {
                            categories.add(BackupCategory.valueOf(categoriesArray.getString(i)))
                        } catch (_: IllegalArgumentException) { }
                    }
                    zis.close()
                    fis.close()
                    return categories
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            fis.close()
            return null // legacy format
        } catch (e: Exception) {
            e.printStackTrace()
            return emptySet()
        }
    }

    suspend fun restoreBackupData(
        context: Context,
        uri: Uri,
        categories: Set<BackupCategory>,
    ): Boolean {
        try {
            val fis = context.contentResolver.openInputStream(uri) ?: return false
            val zis = ZipInputStream(fis)

            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                when {
                    zipEntry.name == MANIFEST_FILE -> { /* skip */ }

                    zipEntry.name.startsWith("shared_prefs/")
                            && BackupCategory.ALL_PREFERENCES in categories -> {
                        restorePreferencesEntry(
                            zis,
                            zipEntry.name.removePrefix("shared_prefs/"),
                        )
                    }

                    zipEntry.name == GPT_SETTINGS_FILE
                            && BackupCategory.GPT_SETTINGS in categories
                            && BackupCategory.ALL_PREFERENCES !in categories -> {
                        val content = readEntryBytes(zis, MAX_GPT_SETTINGS_BYTES)
                        importGptSettings(JSONObject(String(content)))
                    }

                    zipEntry.name == BOOKMARKS_FILE
                            && BackupCategory.BOOKMARKS in categories -> {
                        val content = readEntryBytes(zis, MAX_COLLECTION_ENTRY_BYTES)
                        val bookmarks = JSONArray(String(content))
                            .toJSONObjectList()
                            .map { it.toBookmark() }
                        kotlinx.coroutines.runBlocking {
                            bookmarkManager.overwriteBookmarks(bookmarks)
                        }
                    }

                    zipEntry.name == HISTORY_FILE
                            && BackupCategory.HISTORY in categories -> {
                        val content = readEntryBytes(zis, MAX_COLLECTION_ENTRY_BYTES)
                        val jsonArray = JSONArray(String(content))
                        val records = (0 until jsonArray.length()).map { i ->
                            val obj = jsonArray.getJSONObject(i)
                            Record(
                                obj.optString("title"),
                                obj.optString("url"),
                                obj.optLong("time"),
                            )
                        }
                        recordDb.replaceAllHistory(records)
                    }

                    zipEntry.name == DATABASE_DATA_FILE
                            && BackupCategory.DATABASE_DATA in categories -> {
                        val content = readEntryBytes(zis, MAX_DATABASE_DATA_BYTES)
                        restoreDatabaseData(JSONObject(String(content)))
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            fis.close()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getAvailableCategories(file: File): Set<BackupCategory>? {
        try {
            val zis = ZipInputStream(file.inputStream())
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name == MANIFEST_FILE) {
                    val content = readEntryBytes(zis, MAX_MANIFEST_BYTES)
                    val manifest = JSONObject(String(content))
                    val categoriesArray = manifest.getJSONArray("categories")
                    val categories = mutableSetOf<BackupCategory>()
                    for (i in 0 until categoriesArray.length()) {
                        try {
                            categories.add(BackupCategory.valueOf(categoriesArray.getString(i)))
                        } catch (_: IllegalArgumentException) { }
                    }
                    zis.close()
                    return categories
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return emptySet()
        }
    }

    suspend fun restoreBackupData(
        file: File,
        categories: Set<BackupCategory>,
    ): Boolean {
        try {
            val zis = ZipInputStream(file.inputStream())
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                when {
                    zipEntry.name == MANIFEST_FILE -> { /* skip */ }

                    zipEntry.name.startsWith("shared_prefs/")
                            && BackupCategory.ALL_PREFERENCES in categories -> {
                        restorePreferencesEntry(
                            zis,
                            zipEntry.name.removePrefix("shared_prefs/"),
                        )
                    }

                    zipEntry.name == GPT_SETTINGS_FILE
                            && BackupCategory.GPT_SETTINGS in categories
                            && BackupCategory.ALL_PREFERENCES !in categories -> {
                        val content = readEntryBytes(zis, MAX_GPT_SETTINGS_BYTES)
                        importGptSettings(JSONObject(String(content)))
                    }

                    zipEntry.name == BOOKMARKS_FILE
                            && BackupCategory.BOOKMARKS in categories -> {
                        val content = readEntryBytes(zis, MAX_COLLECTION_ENTRY_BYTES)
                        val bookmarks = JSONArray(String(content))
                            .toJSONObjectList()
                            .map { it.toBookmark() }
                        kotlinx.coroutines.runBlocking {
                            bookmarkManager.overwriteBookmarks(bookmarks)
                        }
                    }

                    zipEntry.name == HISTORY_FILE
                            && BackupCategory.HISTORY in categories -> {
                        val content = readEntryBytes(zis, MAX_COLLECTION_ENTRY_BYTES)
                        val jsonArray = JSONArray(String(content))
                        val records = (0 until jsonArray.length()).map { i ->
                            val obj = jsonArray.getJSONObject(i)
                            Record(
                                obj.optString("title"),
                                obj.optString("url"),
                                obj.optLong("time"),
                            )
                        }
                        recordDb.replaceAllHistory(records)
                    }

                    zipEntry.name == DATABASE_DATA_FILE
                            && BackupCategory.DATABASE_DATA in categories -> {
                        val content = readEntryBytes(zis, MAX_DATABASE_DATA_BYTES)
                        restoreDatabaseData(JSONObject(String(content)))
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /** Legacy restore: restores everything from old-format zip (no manifest). */
    fun restoreLegacyBackupData(context: Context, uri: Uri): Boolean {
        try {
            bookmarkManager.database.close()

            val fis = context.contentResolver.openInputStream(uri) ?: return false
            val zis = ZipInputStream(fis)

            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                if (zipEntry.isDirectory) {
                    zipEntry = zis.nextEntry
                    continue
                }
                val fileName = requireSafeBackupBasename(zipEntry.name)
                if (fileName == SecretStore.KEYSET_PREF_XML) {
                    zipEntry = zis.nextEntry
                    continue
                }
                if (fileName.endsWith(".db") || fileName.contains("einkbro_db")) {
                    writeStreamToFile(
                        zis,
                        resolveRestoreTarget(databasesDir, fileName),
                        MAX_LEGACY_DATABASE_BYTES,
                    )
                } else {
                    restorePreferencesEntry(zis, fileName)
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            fis.close()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private suspend fun restoreDatabaseData(json: JSONObject) {
        val db = bookmarkManager.database

        // Favicons
        if (json.has("favicons")) {
            val arr = json.getJSONArray("favicons")
            val favicons = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FaviconInfo(
                    domain = obj.getString("domain"),
                    icon = if (obj.isNull("icon")) null
                        else Base64.decode(obj.getString("icon"), Base64.NO_WRAP)
                )
            }
            db.faviconDao().deleteAll()
            db.faviconDao().insertAll(favicons)
        }

        // Articles (restore before highlights due to foreign key)
        if (json.has("articles")) {
            val arr = json.getJSONArray("articles")
            val articles = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Article(
                    title = obj.getString("title"),
                    url = obj.getString("url"),
                    date = obj.getLong("date"),
                    tags = obj.optString("tags", ""),
                ).apply { id = obj.getInt("id") }
            }
            db.highlightDao().deleteAll()
            db.articleDao().deleteAll()
            db.articleDao().insertAll(articles)
        }

        // Highlights
        if (json.has("highlights")) {
            val arr = json.getJSONArray("highlights")
            val highlights = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Highlight(
                    articleId = obj.getInt("articleId"),
                    content = obj.getString("content"),
                ).apply { id = obj.getInt("id") }
            }
            db.highlightDao().insertAll(highlights)
        }

        // ChatGptQuery
        if (json.has("chat_gpt_queries")) {
            val arr = json.getJSONArray("chat_gpt_queries")
            val queries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatGptQuery(
                    date = obj.getLong("date"),
                    url = obj.getString("url"),
                    model = obj.getString("model"),
                    selectedText = obj.getString("selectedText"),
                    result = obj.getString("result"),
                ).apply { id = obj.getInt("id") }
            }
            db.chatGptQueryDao().deleteAll()
            db.chatGptQueryDao().insertAll(queries)
        }

        // DomainConfiguration
        if (json.has("domain_configurations")) {
            val arr = json.getJSONArray("domain_configurations")
            val configs = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                DomainConfiguration(
                    domain = obj.getString("domain"),
                    configuration = obj.getString("configuration"),
                )
            }
            db.domainConfigurationDao().deleteAll()
            db.domainConfigurationDao().insertAll(configs)
        }

        // SavedPage
        if (json.has("saved_pages")) {
            val arr = json.getJSONArray("saved_pages")
            val pages = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SavedPage(
                    title = obj.getString("title"),
                    url = obj.getString("url"),
                    filePath = obj.getString("filePath"),
                    savedAt = obj.getLong("savedAt"),
                ).apply { id = obj.getInt("id") }
            }
            db.savedPageDao().deleteAll()
            db.savedPageDao().insertAll(pages)
        }

        // Domain lists
        if (json.has("whitelist_domains")) {
            val arr = json.getJSONArray("whitelist_domains")
            val domains = (0 until arr.length()).map { WhitelistDomain(arr.getString(it)) }
            db.domainListDao().deleteAllWhitelist()
            db.domainListDao().insertAllWhitelist(domains)
        }

        if (json.has("javascript_domains")) {
            val arr = json.getJSONArray("javascript_domains")
            val domains = (0 until arr.length()).map { JavascriptDomain(arr.getString(it)) }
            db.domainListDao().deleteAllJavascript()
            db.domainListDao().insertAllJavascript(domains)
        }

        if (json.has("cookie_domains")) {
            val arr = json.getJSONArray("cookie_domains")
            val domains = (0 until arr.length()).map { CookieDomain(arr.getString(it)) }
            db.domainListDao().deleteAllCookie()
            db.domainListDao().insertAllCookie(domains)
        }
    }

    private fun exportGptSettings(): JSONObject {
        val json = JSONObject()
        val allPrefs = sp.all
        for (key in GPT_PREF_KEYS) {
            val value = allPrefs[key] ?: continue
            when (value) {
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is String -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        return json
    }

    private fun importGptSettings(json: JSONObject) {
        // Backups from older app versions carry the API keys inside
        // gpt_settings.json; route those into the encrypted store instead of
        // re-planting them in the plaintext SharedPreferences.
        val legacySecrets = mutableMapOf<String, String>()
        sp.edit {
            for (key in json.keys()) {
                if (key in SecretKeys.ALL) {
                    json.get(key).toString().takeIf { it.isNotEmpty() }
                        ?.let { legacySecrets[key] = it }
                    continue
                }
                when (val value = json.get(key)) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Double -> putFloat(key, value.toFloat())
                    is String -> putString(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }
        secretPrefs.putAll(legacySecrets)
    }

    /** Reads the passphrase-encrypted secrets envelope from a backup zip, or null
     *  when the zip has none. */
    fun readSecretsEnvelope(context: Context, uri: Uri): JSONObject? = try {
        context.contentResolver.openInputStream(uri)?.use { fis ->
            readSecretsEnvelope(ZipInputStream(fis))
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun readSecretsEnvelope(file: File): JSONObject? = try {
        ZipInputStream(file.inputStream()).use { readSecretsEnvelope(it) }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun readSecretsEnvelope(zis: ZipInputStream): JSONObject? {
        var zipEntry = zis.nextEntry
        while (zipEntry != null) {
            if (zipEntry.name == SECRETS_FILE) {
                return JSONObject(
                    String(
                        readEntryBytes(zis, MAX_SECRETS_ENVELOPE_BYTES),
                        Charsets.UTF_8,
                    )
                )
            }
            zipEntry = zis.nextEntry
        }
        return null
    }

    /** Decrypts [envelope] with [passphrase] and stores the secrets in the
     *  encrypted store. @return false on a wrong passphrase or unreadable data. */
    suspend fun restoreSecrets(envelope: JSONObject, passphrase: String): Boolean =
        withContext(Dispatchers.Default) {
            val values = PassphraseCipher().decrypt(envelope, passphrase.toCharArray())
                ?: return@withContext false
            secretPrefs.putAll(values.filterKeys { it in SecretKeys.BACKUP })
            true
        }

    private fun writeFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val fis = FileInputStream(file)
        zos.putNextEntry(ZipEntry(entryName))
        fis.copyTo(zos)
        zos.closeEntry()
        fis.close()
    }

    private fun writeDirectoryToZip(zos: ZipOutputStream, dir: File, zipPrefix: String) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val entryPath = zipPrefix + file.name
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                writeDirectoryToZip(zos, file, "$entryPath/")
            } else {
                writeFileToZip(zos, file, entryPath)
            }
        }
    }

    /**
     * The default SharedPreferences file is named "<packageName>_preferences.xml".
     * When restoring a backup made under a different applicationId (the real app into
     * the `.a` build, or vice versa), rewrite that name to the current package so this
     * app actually reads the restored values. Other prefs files keep their fixed names
     * (ad-filter's "io.github.edsuns.filter", WebView's), so they restore as-is.
     * No-op when source and target package already match.
     */
    private fun remapPrefsFileName(name: String): String =
        if (name.endsWith("_preferences.xml")) "${context.packageName}_preferences.xml" else name

    private fun restorePreferencesEntry(zis: ZipInputStream, rawFileName: String) {
        val safeFileName = requireSafeBackupBasename(rawFileName)
        // Never overwrite the local Tink keyset with a foreign one: that would
        // make this device's stored secrets undecryptable.
        if (safeFileName == SecretStore.KEYSET_PREF_XML) return

        val remappedName = remapPrefsFileName(safeFileName)
        val target = resolveRestoreTarget(sharedPrefsDir, remappedName)
        if (remappedName == "${context.packageName}_preferences.xml") {
            val restored = scrubSecretsFromPreferencesXml(
                readEntryBytes(zis, MAX_PREFERENCES_FILE_BYTES)
            )
            if (restored.secrets.isNotEmpty()) {
                secretPrefs.putAll(restored.secrets)
            }
            writeBytesToFile(restored.xml, target)
        } else {
            writeStreamToFile(zis, target, MAX_PREFERENCES_FILE_BYTES)
        }
    }

    private fun requireSafeBackupBasename(name: String): String {
        if (
            name.isEmpty() ||
            name == "." ||
            name == ".." ||
            name.contains('/') ||
            name.contains('\\') ||
            File(name).isAbsolute
        ) {
            throw IOException("Unsafe backup entry name")
        }
        return name
    }

    private fun resolveRestoreTarget(root: File, fileName: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, requireSafeBackupBasename(fileName)).canonicalFile
        if (target.parentFile != canonicalRoot) {
            throw IOException("Backup entry escapes its restore directory")
        }
        return target
    }

    private data class ScrubbedPreferences(
        val xml: ByteArray,
        val secrets: Map<String, String>,
    )

    private fun scrubSecretsFromPreferencesXml(xml: ByteArray): ScrubbedPreferences {
        val text = String(xml, Charsets.UTF_8)
        if (
            text.contains("<!DOCTYPE", ignoreCase = true) ||
            text.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IOException("Unsafe preferences XML")
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = document.documentElement ?: throw IOException("Missing preferences root")
        if (root.tagName != "map") throw IOException("Invalid preferences root")

        val secrets = mutableMapOf<String, String>()
        val secretNames = (SecretKeys.ALL + SecretKeys.DEPRECATED_DEVICE_OAUTH).toSet()
        val children = root.childNodes
        for (index in children.length - 1 downTo 0) {
            val node = children.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            if (name !in secretNames) continue
            if (name in SecretKeys.ALL && node.nodeName == "string") {
                node.textContent.takeIf { it.isNotEmpty() }?.let { secrets[name] = it }
            }
            root.removeChild(node)
        }

        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
        return ScrubbedPreferences(output.toByteArray(), secrets)
    }

    private fun readEntryBytes(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(BACKUP_COPY_BUFFER_SIZE, maxBytes))
        val buffer = ByteArray(BACKUP_COPY_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zis.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Backup entry exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeBytesToFile(bytes: ByteArray, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun writeStreamToFile(zis: ZipInputStream, file: File, maxBytes: Int) {
        writeBytesToFile(readEntryBytes(zis, maxBytes), file)
    }

    fun importBookmarks(uri: Uri) {
        coroutineScope.launch {
            try {
                val contentString = getFileContentString(uri)
                // detect if the content is a json array
                val bookmarks = if (contentString.startsWith("[")) {
                    JSONArray(contentString).toJSONObjectList().map { json -> json.toBookmark() }
                } else {
                    //parseHtmlToBookmarkList(contentString)
                    parseChromeBookmarks(contentString)
                }

                if (bookmarks.isNotEmpty()) {
                    bookmarkManager.overwriteBookmarks(bookmarks)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Bookmarks are imported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Bookmarks import failed", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private suspend fun getFileContentString(uri: Uri): String {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use {
                it?.bufferedReader()?.readText().orEmpty()
            }
        }
    }

    private var recordId = 0
    private fun parseChromeBookmarks(html: String): List<Bookmark> {
        val doc = Jsoup.parse(html)
        val bookmarks = dlElement(doc.select("DL").first()!!.children(), recordId)
        recordId = 0
        return bookmarks
    }

    private fun dlElement(elements: Elements, parentId: Int): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        for (elem in elements) {
            when (elem.nodeName().uppercase()) {
                "DT" -> bookmarkList.addAll(dtElement(elem.children(), parentId))
                "DL" -> bookmarkList.addAll(dlElement(elem.children(), parentId))
                "P" -> continue
                else -> {}
            }
        }
        return bookmarkList
    }

    private var currentFolderId = 0
    private fun dtElement(elements: Elements, parentId: Int): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        for (elem in elements) {
            when (elem.nodeName().uppercase()) {
                "H3" -> {
                    currentFolderId = ++recordId
                    bookmarkList.add(
                        Bookmark(
                            elem.text(),
                            "",
                            true,
                            parentId,
                        ).apply { id = currentFolderId }
                    )
                }

                "A" -> bookmarkList.add(
                    Bookmark(
                        elem.text(),
                        elem.attr("href"),
                        false,
                        parentId,
                    ).apply { id = ++recordId }
                )

                "DL" -> bookmarkList.addAll(dlElement(elem.children(), currentFolderId))
                "P" -> continue
                else -> {}
            }
        }
        return bookmarkList
    }

    private fun elementToBookmarks(element: Element): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        val bookmarkElements = element.select("a")
        for (bookmarkElement in bookmarkElements) {
            val bookmark = Bookmark(
                bookmarkElement.text(),
                bookmarkElement.attr("href"),
            )
            bookmarkList.add(bookmark)
        }
        return bookmarkList
    }

    private fun JSONArray.toJSONObjectList() =
        (0 until length()).map { get(it) as JSONObject }

    fun exportBookmarks(uri: Uri, showToast: Boolean = true) {
        coroutineScope.launch {
            val bookmarks = bookmarkManager.getAllBookmarks()
            try {
                context.contentResolver.openOutputStream(uri).use {
                    it?.write(bookmarks.toJsonString().toByteArray())
                }
                if (showToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmarks are exported", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (showToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmarks export failed", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    fun preprocessActivityResult(result: ActivityResult): Uri? {
        if (result.resultCode != FragmentActivity.RESULT_OK) return null
        val uri = result.data?.data ?: return null
        context.contentResolver
            .takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        return uri
    }

    fun exportDataToFileUri(uri: Uri, data: String) {
        val fileContent = data.toByteArray()

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(fileContent)
        }
    }

    private fun shareFile(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/html"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, "Share via"))
    }


    companion object {
        private const val MANIFEST_FILE = "_manifest.json"
        private const val GPT_SETTINGS_FILE = "gpt_settings.json"
        private const val BOOKMARKS_FILE = "bookmarks.json"
        private const val HISTORY_FILE = "history.json"
        private const val DATABASE_DATA_FILE = "database_data.json"
        private const val SECRETS_FILE = "secrets.enc"
        private const val MAX_SECRETS_ENVELOPE_BYTES = 256 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_GPT_SETTINGS_BYTES = 4 * 1024 * 1024
        private const val MAX_COLLECTION_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_DATABASE_DATA_BYTES = 128 * 1024 * 1024
        private const val MAX_PREFERENCES_FILE_BYTES = 16 * 1024 * 1024
        private const val MAX_LEGACY_DATABASE_BYTES = 256 * 1024 * 1024
        private const val BACKUP_COPY_BUFFER_SIZE = 8 * 1024

        // API keys deliberately absent: secrets are exported only inside the
        // passphrase-encrypted SECRETS category (secrets.enc), never in this
        // plaintext JSON. importGptSettings still accepts them from old backups.
        private val GPT_PREF_KEYS = listOf(
            "sp_gpt_system_prompt",
            "sp_gpt_user_prompt",
            "sp_gpt_user_prompt_web_page",
            "sp_gp_model",
            "sp_gpt_voice_model",
            "sp_gpt_voice_prompt",
            "sp_alternative_model",
            "sp_gemini_model",
            "sp_use_openai_tts",
            "sp_external_search_with_gpt",
            "sp_enable_open_ai_stream",
            "sp_gpt_action_items",
            "sp_gpt_action_external",
            "sp_gpt_for_chat_web",
            "sp_gpt_for_summary",
            "sp_gpt_server_url",
            "sp_use_custom_gpt_url",
            "sp_use_gemini_api",
            "K_GPT_VOICE_OPTION",
        )
    }
}

private fun List<Bookmark>.toJsonString(): String {
    val jsonArrays = JSONArray()
    this.map { it.toJsonObject() }.forEach { jsonArrays.put(it) }

    return jsonArrays.toString()
}

private fun Bookmark.toJsonObject(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("isDirectory", isDirectory)
        put("parent", parent)
        put("order", order)
    }

private fun JSONObject.toBookmark(): Bookmark =
    Bookmark(
        optString("title"),
        optString("url"),
        optBoolean("isDirectory"),
        optInt("parent"),
        optInt("order")
    ).apply { id = optInt("id") }
