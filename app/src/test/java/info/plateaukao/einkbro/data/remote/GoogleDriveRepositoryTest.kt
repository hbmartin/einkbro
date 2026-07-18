package info.plateaukao.einkbro.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class GoogleDriveRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: GoogleDriveRepository

    @Before
    fun setUp() {
        server.start()
        repository = GoogleDriveRepository(
            client = OkHttpClient(),
            filesEndpoint = server.url("/drive/v3/files"),
            uploadEndpoint = server.url("/upload/drive/v3/files"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authorization requires the app-data scope and a token`() = runBlocking {
        assertFalse(repository.acceptAuthorization("token", emptyList()))
        assertFalse(repository.acceptAuthorization("", listOf(GoogleDriveRepository.SCOPE)))

        val error = runCatching { repository.getRemoteBackup() }.exceptionOrNull()
        assertTrue(error is DriveReauthRequiredException)
        assertEquals(0, server.requestCount)

        assertTrue(
            repository.acceptAuthorization(
                "token",
                listOf(GoogleDriveRepository.SCOPE),
            )
        )
    }

    @Test
    fun `create uses a resumable session and supports files larger than five MiB`() = runBlocking {
        authorize()
        server.enqueue(driveFileList())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Location", server.url("/resumable/session"))
        )
        server.enqueue(driveFile("created-id"))
        val backup = File.createTempFile("drive-backup", ".zip").apply {
            outputStream().use { output ->
                val block = ByteArray(64 * 1024) { 7 }
                repeat(81) { output.write(block) }
            }
        }

        try {
            val uploaded = repository.uploadBackup(backup)

            assertEquals("created-id", uploaded.id)
            val lookup = server.takeRequest()
            assertEquals("GET", lookup.method)

            val start = server.takeRequest()
            assertEquals("POST", start.method)
            assertEquals("resumable", start.requestUrl?.queryParameter("uploadType"))
            assertEquals(backup.length().toString(), start.getHeader("X-Upload-Content-Length"))
            assertEquals("application/zip", start.getHeader("X-Upload-Content-Type"))
            assertEquals(
                """{"name":"einkbro-backup.zip","parents":["appDataFolder"]}""",
                start.body.readUtf8(),
            )

            val upload = server.takeRequest()
            assertEquals("PUT", upload.method)
            assertEquals("/resumable/session", upload.path)
            assertEquals(backup.length(), upload.bodySize)
            assertEquals(
                "bytes 0-${backup.length() - 1}/${backup.length()}",
                upload.getHeader("Content-Range"),
            )
            assertEquals("Bearer test-token", upload.getHeader("Authorization"))
            assertTrue(backup.length() > 5L * 1024 * 1024)
        } finally {
            backup.delete()
        }
    }

    @Test
    fun `update starts a PATCH resumable session for the existing file`() = runBlocking {
        authorize()
        server.enqueue(driveFileList("existing-id"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Location", server.url("/resumable/update"))
        )
        server.enqueue(driveFile("existing-id"))
        val backup = File.createTempFile("drive-backup", ".zip").apply {
            writeText("backup")
        }

        try {
            repository.uploadBackup(backup)
            server.takeRequest()
            val start = server.takeRequest()

            assertEquals("PATCH", start.method)
            assertEquals("/upload/drive/v3/files/existing-id", start.requestUrl?.encodedPath)
            assertEquals("resumable", start.requestUrl?.queryParameter("uploadType"))
            assertEquals("{}", start.body.readUtf8())
            assertEquals("PUT", server.takeRequest().method)
        } finally {
            backup.delete()
        }
    }

    @Test
    fun `http 401 clears the token and identifies it for cache eviction`() = runBlocking {
        authorize()
        server.enqueue(MockResponse().setResponseCode(401))

        val first = runCatching { repository.getRemoteBackup() }
            .exceptionOrNull() as DriveReauthRequiredException
        assertEquals("test-token", first.invalidAccessToken)

        val second = runCatching { repository.getRemoteBackup() }.exceptionOrNull()
        assertTrue(second is DriveReauthRequiredException)
        assertNull((second as DriveReauthRequiredException).invalidAccessToken)
        assertEquals(1, server.requestCount)
    }

    private fun authorize() {
        assertTrue(
            repository.acceptAuthorization(
                "test-token",
                listOf(GoogleDriveRepository.SCOPE),
            )
        )
    }

    private fun driveFileList(id: String? = null): MockResponse {
        val body = if (id == null) {
            """{"files":[]}"""
        } else {
            """{"files":[{"id":"$id","name":"einkbro-backup.zip"}]}"""
        }
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun driveFile(id: String): MockResponse =
        MockResponse().setResponseCode(200)
            .setBody("""{"id":"$id","name":"einkbro-backup.zip"}""")
}
