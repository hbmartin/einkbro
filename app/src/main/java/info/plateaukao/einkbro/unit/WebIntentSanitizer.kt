package info.plateaukao.einkbro.unit

import android.content.Intent

/**
 * Hardens intents parsed from web content (intent:// URLs) before startActivity,
 * mirroring Chrome's external-navigation sanitization: only BROWSABLE activities
 * can be targeted, and pages cannot pick an explicit component/selector or smuggle
 * launch/grant flags. `package` is intentionally preserved — legitimate intent://
 * links use it to pin the target app and drive the Play Store fallback.
 */
object WebIntentSanitizer {
    fun sanitize(intent: Intent): Intent = intent.apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        // safe even with package set; AOSP rejects only a non-null selector + package
        selector = null
        // drops launchFlags= embedded in the intent URI
        flags = 0
    }

    /** Only plain web URLs may be loaded back into the WebView as intent fallback. */
    fun isSafeFallbackUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }
}
