package info.plateaukao.einkbro.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@Serializable
data class DriveFileMeta(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
)

@Serializable
private data class DriveFileList(
    val files: List<DriveFileMeta> = emptyList(),
)

class DriveReauthRequiredException(
    val invalidAccessToken: String? = null,
) : Exception("Google Drive authorization is required")

/**
 * Handles Drive's app-data API using a short-lived access token supplied by Google Play Services.
 *
 * The token deliberately lives only in memory. Call [acceptAuthorization] for every user-initiated
 * sync session and [clearAuthorization] when the session ends.
 */
class GoogleDriveRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val filesEndpoint: HttpUrl = DRIVE_FILES_ENDPOINT.toHttpUrl(),
    private val uploadEndpoint: HttpUrl = DRIVE_UPLOAD_ENDPOINT.toHttpUrl(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var accessToken: String? = null

    fun acceptAuthorization(
        accessToken: String?,
        grantedScopes: Collection<String>,
    ): Boolean {
        val usableToken = accessToken?.takeIf(String::isNotBlank)
        if (usableToken == null || SCOPE !in grantedScopes) {
            clearAuthorization()
            return false
        }

        this.accessToken = usableToken
        return true
    }

    fun clearAuthorization() {
        accessToken = null
    }

    suspend fun getRemoteBackup(): DriveFileMeta? = withAccessToken { token ->
        val url = filesEndpoint.newBuilder()
            .addQueryParameter("spaces", APP_DATA_FOLDER)
            .addQueryParameter("q", "name = '$BACKUP_FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "1")
            .build()

        execute(Request.Builder().url(url).authorized(token).get().build()).use { response ->
            Json.decodeFromString<DriveFileList>(response.body!!.string()).files.firstOrNull()
        }
    }

    suspend fun uploadBackup(file: File): DriveFileMeta = withAccessToken { token ->
        val existingFile = findRemoteBackup(token)
        val sessionUrl = beginResumableUpload(file, existingFile?.id, token)
        uploadToSession(file, sessionUrl, token)
    }

    suspend fun downloadBackup(
        fileId: String,
        destination: File,
    ) = withAccessToken { token ->
        val url = filesEndpoint.newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()

        execute(Request.Builder().url(url).authorized(token).get().build()).use { response ->
            response.body!!.byteStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
    }

    private fun findRemoteBackup(token: String): DriveFileMeta? {
        val url = filesEndpoint.newBuilder()
            .addQueryParameter("spaces", APP_DATA_FOLDER)
            .addQueryParameter("q", "name = '$BACKUP_FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "1")
            .build()

        return execute(Request.Builder().url(url).authorized(token).get().build()).use { response ->
            Json.decodeFromString<DriveFileList>(response.body!!.string()).files.firstOrNull()
        }
    }

    private fun beginResumableUpload(
        file: File,
        existingFileId: String?,
        token: String,
    ): HttpUrl {
        val urlBuilder = uploadEndpoint.newBuilder()
        if (existingFileId != null) {
            urlBuilder.addPathSegment(existingFileId)
        }
        val url = urlBuilder
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,modifiedTime")
            .build()
        val metadata = if (existingFileId == null) {
            """{"name":"$BACKUP_FILE_NAME","parents":["$APP_DATA_FOLDER"]}"""
        } else {
            "{}"
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .authorized(token)
            .header("X-Upload-Content-Type", ZIP_MEDIA_TYPE.toString())
            .header("X-Upload-Content-Length", file.length().toString())

        val request = if (existingFileId == null) {
            requestBuilder.post(metadata.toRequestBody(JSON_MEDIA_TYPE)).build()
        } else {
            requestBuilder.patch(metadata.toRequestBody(JSON_MEDIA_TYPE)).build()
        }

        return execute(request).use { response ->
            response.header("Location")?.toHttpUrlOrNull()
                ?: error("Drive resumable upload response did not include a session URL")
        }
    }

    private fun uploadToSession(
        file: File,
        sessionUrl: HttpUrl,
        token: String,
    ): DriveFileMeta {
        val contentLength = file.length()
        val contentRange = if (contentLength == 0L) {
            "bytes */0"
        } else {
            "bytes 0-${contentLength - 1}/$contentLength"
        }
        val request = Request.Builder()
            .url(sessionUrl)
            .authorized(token)
            .header("Content-Range", contentRange)
            .put(file.asRequestBody(ZIP_MEDIA_TYPE))
            .build()

        return execute(request).use { response ->
            Json.decodeFromString(response.body!!.string())
        }
    }

    private fun execute(request: Request): okhttp3.Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw DriveHttpException(code)
        }
        return response
    }

    private suspend fun <T> withAccessToken(block: (String) -> T): T =
        withContext(ioDispatcher) {
            val token = accessToken ?: throw DriveReauthRequiredException()
            try {
                block(token)
            } catch (error: DriveHttpException) {
                if (error.code == 401) {
                    clearAuthorization()
                    throw DriveReauthRequiredException(token)
                }
                throw error
            }
        }

    private fun Request.Builder.authorized(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private class DriveHttpException(val code: Int) :
        Exception("Google Drive request failed with HTTP $code")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val BACKUP_FILE_NAME = "einkbro-backup.zip"

        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
    }
}
