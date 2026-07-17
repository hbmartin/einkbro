package info.plateaukao.einkbro.unit

import android.content.Intent
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebIntentSanitizerTest {

    @Test
    fun `sanitize forces BROWSABLE and strips component, selector and flags`() {
        val intent = mockk<Intent>(relaxed = true)

        val result = WebIntentSanitizer.sanitize(intent)

        assertSame(intent, result)
        verify { intent.addCategory(Intent.CATEGORY_BROWSABLE) }
        verify { intent.component = null }
        verify { intent.selector = null }
        verify { intent.flags = 0 }
    }

    @Test
    fun `web fallback urls are allowed`() {
        assertTrue(WebIntentSanitizer.isSafeFallbackUrl("http://example.com"))
        assertTrue(WebIntentSanitizer.isSafeFallbackUrl("https://example.com/path?q=1"))
        assertTrue(WebIntentSanitizer.isSafeFallbackUrl("HTTPS://EXAMPLE.COM"))
        assertTrue(WebIntentSanitizer.isSafeFallbackUrl("  https://example.com  "))
    }

    @Test
    fun `non-web fallback urls are rejected`() {
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("javascript:alert(1)"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("file:///data/data/pkg/secret"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("intent://foo#Intent;end"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("content://provider/item"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("market://details?id=app"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("data:text/html,<script>1</script>"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl(""))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("http:/missing-slash"))
        assertFalse(WebIntentSanitizer.isSafeFallbackUrl("httpsx://example.com"))
    }
}
