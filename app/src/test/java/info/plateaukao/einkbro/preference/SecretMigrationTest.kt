package info.plateaukao.einkbro.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretMigrationTest {
    @Test
    fun `sweep migrates supported secrets and purges obsolete OAuth state`() {
        val plain = FakeSharedPreferences().apply {
            store[AiConfig.K_GPT_API_KEY] = "api-key"
            store["sp_drive_auth_state"] = """{"refresh_token":"plaintext-token"}"""
            store["sp_drive_pending_auth"] = "pending-state"
            store["unrelated"] = "keep-me"
        }
        val encrypted = FakeSecretPrefs(
            mutableMapOf(
                ConfigManager.K_INSTAPAPER_PASSWORD to "password",
                "sp_drive_auth_state" to """{"refresh_token":"encrypted-token"}""",
            )
        )

        assertTrue(SecretMigration.sweep(plain, encrypted))

        assertEquals("api-key", encrypted.values[AiConfig.K_GPT_API_KEY])
        assertEquals("password", encrypted.values[ConfigManager.K_INSTAPAPER_PASSWORD])
        assertFalse(encrypted.values.containsKey("sp_drive_auth_state"))
        assertFalse(encrypted.values.containsKey("sp_drive_pending_auth"))
        assertFalse(plain.contains(AiConfig.K_GPT_API_KEY))
        assertFalse(plain.contains("sp_drive_auth_state"))
        assertFalse(plain.contains("sp_drive_pending_auth"))
        assertEquals("keep-me", plain.getString("unrelated", null))
        assertFalse(SecretMigration.sweep(plain, encrypted))
    }

    @Test
    fun `sweep fails when plaintext deletion is not committed`() {
        val plain = FakeSharedPreferences().apply {
            store[AiConfig.K_GPT_API_KEY] = "api-key"
            commitSucceeds = false
        }
        val encrypted = FakeSecretPrefs()

        assertThrows(IllegalStateException::class.java) {
            SecretMigration.sweep(plain, encrypted)
        }

        assertTrue(plain.contains(AiConfig.K_GPT_API_KEY))
    }
}
