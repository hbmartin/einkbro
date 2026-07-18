package info.plateaukao.einkbro.userscript

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-document capability tokens for the einkbroGM JS bridge.
 *
 * addJavascriptInterface exposes the bridge to every page and frame in the WebView and
 * Android provides no caller attribution, so the bridge must not trust a JS-supplied
 * script id (ids are sequential Room keys — trivially guessable). Each userscript
 * injection is instead handed an unguessable token, which the bridge resolves back to
 * the owning script id; calls without a valid token are denied.
 *
 * Tokens are stable per script within one document: page callbacks re-fire on the same
 * document (redirects, progressive commits) and re-inject, while the shim's per-document
 * dedupe guard keeps the first copy alive — re-issuing the same token keeps that live
 * copy working. [clear] runs when the document URL changes, so a token never outlives
 * the page it was issued for.
 *
 * Threading: [issueToken] and [clear] run on the main thread (WebViewClient callbacks);
 * [resolve] runs on the WebView's JS-bridge thread.
 */
class UserScriptTokenRegistry(
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val tokenToScriptId = ConcurrentHashMap<String, Long>()
    private val scriptIdToToken = ConcurrentHashMap<Long, String>()

    fun issueToken(scriptId: Long): String {
        val token = scriptIdToToken.getOrPut(scriptId) { tokenGenerator() }
        tokenToScriptId[token] = scriptId
        return token
    }

    fun resolve(token: String?): Long? = token?.let { tokenToScriptId[it] }

    fun isValid(token: String?): Boolean = resolve(token) != null

    fun clear() {
        tokenToScriptId.clear()
        scriptIdToToken.clear()
    }
}
