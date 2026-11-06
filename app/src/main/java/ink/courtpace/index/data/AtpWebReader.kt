package ink.courtpace.index.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Reads a page after its JavaScript has run.
 *
 * The ATP stats pages ship an empty shell over plain HTTP - the numbers are injected client-side
 * - so a browser engine is required. This loads the URL in an off-screen WebView, waits for the
 * page to fill in, and returns the rendered text.
 *
 * Two things here are deliberate, both learned the hard way:
 *
 * - The deadline is a coroutine timeout, not a postDelayed on the WebView. A detached WebView's
 *   handler may never run, and the first version hung forever on the very first page because of
 *   it: the row said "reading ATP match stats" for six minutes and nothing else happened.
 * - Polling is scheduled on the main looper for the same reason.
 */
class AtpWebReader(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(
        url: String,
        timeoutMs: Long = 25_000,
        /** Called with each render; return true once the page holds what was asked for. */
        isReady: (String) -> Boolean = { it.isNotBlank() },
        /**
         * Wait for the page to stop growing instead of returning at the first sign of content.
         *
         * The results page fills in progressively, so returning the moment one match appeared
         * captured a fraction of the day's play - which is why a "complete" reading kept coming
         * back with six or seven matches.
         */
        settle: Boolean = false,
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false
                var polling = false
                var lastLength = 0
                var stablePolls = 0

                fun release(text: String?) {
                    if (finished) return
                    finished = true
                    polling = false
                    runCatching { web.stopLoading(); web.destroy() }
                    if (continuation.isActive) continuation.resume(text)
                }

                fun poll() {
                    if (finished || !polling) return
                    runCatching {
                        web.evaluateJavascript(
                            "(function(){return document.body ? document.body.innerText : ''})()",
                        ) { raw ->
                            val text = decode(raw)
                            if (text == null) {
                                if (!finished) main.postDelayed({ poll() }, POLL_MS)
                            } else if (text.contains(BLOCKED, true)) {
                                release(text)
                            } else if (settle) {
                                // Scroll so lazy sections load, then wait until the text stops
                                // growing. Returning at the first match captured a fraction of
                                // the day's play, which is why "complete" readings came back
                                // with six or seven matches.
                                runCatching {
                                    web.evaluateJavascript(
                                        "(function(){window.scrollTo(0," +
                                            " document.body.scrollHeight);})()",
                                        null,
                                    )
                                }
                                val grew = text.length > lastLength
                                lastLength = text.length
                                stablePolls = if (grew) 0 else stablePolls + 1
                                if (stablePolls >= STABLE_POLLS) {
                                    release(text)
                                } else if (!finished) {
                                    main.postDelayed({ poll() }, POLL_MS)
                                }
                            } else if (isReady(text)) {
                                release(text)
                            } else if (!finished) {
                                main.postDelayed({ poll() }, POLL_MS)
                            }
                        }
                    }.onFailure { release(null) }
                }

                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = USER_AGENT
                }
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loaded: String) {
                        polling = true
                        main.postDelayed({ poll() }, POLL_MS)
                    }
                }
                continuation.invokeOnCancellation { release(null) }
                runCatching { web.loadUrl(url) }.onFailure { release(null) }

                // Polling also starts here: a page that never reports it finished must still be
                // read, because some renders never raise onPageFinished when detached.
                main.postDelayed({
                    if (!finished && !polling) {
                        polling = true
                        poll()
                    }
                }, POLL_MS * 2)
            }
        }
    }

    /**
     * The hrefs on a page.
     *
     * innerText - what [read] returns - contains no links at all, so discovery based on it could
     * never work. Links have to be collected from the DOM before the text is flattened.
     */
    suspend fun readLinks(url: String, timeoutMs: Long = 25_000): List<String> =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val web = WebView(context.applicationContext)
                    var finished = false
                    fun release(links: List<String>) {
                        if (finished) return
                        finished = true
                        runCatching { web.stopLoading(); web.destroy() }
                        if (continuation.isActive) continuation.resume(links)
                    }
                    web.settings.apply {
                        javaScriptEnabled = true
                        userAgentString = USER_AGENT
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loaded: String) {
                            view.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){return Array.from(document.querySelectorAll('a'))" +
                                        ".map(function(a){return a.getAttribute('href')||''})" +
                                        ".filter(Boolean).join('\\n')})()",
                                ) { raw ->
                                    val text = decode(raw).orEmpty()
                                    release(text.split('\n').filter { it.isNotBlank() })
                                }
                            }, 2_500)
                        }
                    }
                    continuation.invokeOnCancellation { release(emptyList()) }
                    runCatching { web.loadUrl(url) }.onFailure { release(emptyList()) }
                }
            }
        } ?: emptyList()

    /** evaluateJavascript hands back a JSON string literal; unwrap it. */
    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    companion object {
        /** What the site returns when it refuses a request. */
        const val BLOCKED = "have been blocked"

        const val POLL_MS = 400L

        /** Consecutive polls with no growth before a settling render is complete. */
        const val STABLE_POLLS = 4

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}
