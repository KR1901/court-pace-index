package ink.courtpace.index.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Reads a page after its JavaScript has run.
 *
 * The ATP stats pages ship an empty shell over plain HTTP - the numbers are injected client-side
 * - so a browser engine is required to see them. This loads the URL in an off-screen WebView,
 * waits for the page to finish, and returns the rendered text.
 *
 * It must run on the main thread, because WebView is a UI object.
 */
class AtpWebReader(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(
        url: String,
        timeoutMs: Long = 25_000,
        /** Called with each render; return true once the page holds what was asked for. */
        isReady: (String) -> Boolean = { it.isNotBlank() },
    ): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false

                fun finish(text: String?) {
                    if (finished) return
                    finished = true
                    runCatching { web.stopLoading(); web.destroy() }
                    if (continuation.isActive) continuation.resume(text)
                }

                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = USER_AGENT
                }
                // onPageFinished fires before a script-driven page has filled itself in, so the
                // text is polled until it holds what the caller is waiting for.
                fun poll() {
                    if (finished) return
                    web.evaluateJavascript(
                        "(function(){return document.body ? document.body.innerText : ''})()",
                    ) { raw ->
                        val text = decode(raw)
                        if (text != null && isReady(text)) {
                            finish(text)
                        } else if (!finished) {
                            web.postDelayed({ poll() }, POLL_MS)
                        }
                    }
                }

                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loaded: String) {
                        view.postDelayed({ poll() }, POLL_MS)
                    }
                }
                continuation.invokeOnCancellation { finish(null) }
                runCatching { web.loadUrl(url) }.onFailure { finish(null) }

                // A page that never settles must not hold the refresh open forever.
                web.postDelayed({ finish(null) }, timeoutMs)
            }
        }

    /** evaluateJavascript hands back a JSON string literal; unwrap it. */
    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private companion object {
        const val POLL_MS = 700L

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}
