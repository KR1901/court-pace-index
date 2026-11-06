package ink.courtpace.index.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Reads ATP pages after their JavaScript has run.
 *
 * The ATP CDN blocks ordinary HTTP clients for these pages, and the match-stat view injects its
 * numbers client-side. A device WebView is therefore the source of truth for both match links
 * and rendered stats.
 */
class AtpWebReader(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(
        url: String,
        timeoutMs: Long = 25_000,
        /** Called with each render; return true once the page holds what was asked for. */
        isReady: (String) -> Boolean = { it.isNotBlank() },
        /** Wait for the page to stop growing instead of returning at the first sign of content. */
        settle: Boolean = false,
        /** Return the latest DOM text if the expected stats marker never appears before timeout. */
        returnLastOnTimeout: Boolean = false,
    ): String? {
        var latestText: String? = null
        val result = withTimeoutOrNull(timeoutMs) {
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
                            if (text != null) latestText = text
                            if (text == null) {
                                if (!finished) main.postDelayed({ poll() }, POLL_MS)
                            } else if (text.contains(BLOCKED, true)) {
                                release(text)
                            } else if (settle) {
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
                        if (!polling && !finished) {
                            polling = true
                            main.postDelayed({ poll() }, POLL_MS)
                        }
                    }
                }
                continuation.invokeOnCancellation { release(null) }
                runCatching { web.loadUrl(url) }.onFailure { release(null) }

                // Some detached WebViews do not report onPageFinished; still start polling.
                main.postDelayed({
                    if (!finished && !polling) {
                        polling = true
                        poll()
                    }
                }, POLL_MS * 2)
            }
        }
        }
        return result ?: if (returnLastOnTimeout) latestText else null
    }

    /**
     * Returns the live DOM snapshot, including href/data-href attributes, after it settles.
     *
     * A fixed 2.5-second delay sampled the ATP hub before its JavaScript populated the event and
     * match links. That empty snapshot triggered the old 96-ID guess. We now wait for the full DOM
     * to stop changing, scrolling as we do so to trigger any lazy-loaded result sections.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun readPageSource(
        url: String,
        timeoutMs: Long = PAGE_SOURCE_TIMEOUT_MS,
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false
                var polling = false
                var lastSnapshot: String? = null
                var stablePolls = 0
                val startedAt = SystemClock.elapsedRealtime()

                fun release(source: String?) {
                    if (finished) return
                    finished = true
                    polling = false
                    runCatching { web.stopLoading(); web.destroy() }
                    if (continuation.isActive) continuation.resume(source)
                }

                fun poll() {
                    if (finished || !polling) return
                    runCatching {
                        web.evaluateJavascript(PAGE_SOURCE_SCRIPT) { raw ->
                            val source = decode(raw)
                            when {
                                source == null -> {
                                    if (!finished) main.postDelayed({ poll() }, POLL_MS)
                                }
                                source.contains(BLOCKED, ignoreCase = true) -> release(source)
                                else -> {
                                    stablePolls = if (source == lastSnapshot) {
                                        stablePolls + 1
                                    } else {
                                        0
                                    }
                                    lastSnapshot = source
                                    val minimumWaitElapsed =
                                        SystemClock.elapsedRealtime() - startedAt >= PAGE_SOURCE_MIN_WAIT_MS
                                    if (stablePolls >= STABLE_POLLS && minimumWaitElapsed) {
                                        release(source)
                                    } else if (!finished) {
                                        main.postDelayed({ poll() }, POLL_MS)
                                    }
                                }
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
                        if (!polling && !finished) {
                            polling = true
                            main.postDelayed({ poll() }, POLL_MS)
                        }
                    }
                }
                continuation.invokeOnCancellation { release(null) }
                runCatching { web.loadUrl(url) }.onFailure { release(null) }

                main.postDelayed({
                    if (!finished && !polling) {
                        polling = true
                        poll()
                    }
                }, POLL_MS * 2)
            }
        }
    }

    /** evaluateJavascript hands back a JSON string literal; unwrap it. */
    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    companion object {
        const val BLOCKED = "have been blocked"
        const val POLL_MS = 400L
        const val STABLE_POLLS = 4
        const val PAGE_SOURCE_TIMEOUT_MS = 45_000L
        const val PAGE_SOURCE_MIN_WAIT_MS = 8_000L

        private val PAGE_SOURCE_SCRIPT = """
            (function() {
              if (document.body) {
                window.scrollTo(0, document.body.scrollHeight);
              }
              var root = document.documentElement;
              var markup = root ? root.outerHTML : '';
              var separator = String.fromCharCode(10);
              var links = Array.from(document.querySelectorAll('a')).map(function(a) {
                return [
                  a.href || '',
                  a.getAttribute('href') || '',
                  a.getAttribute('data-href') || '',
                  a.getAttribute('data-url') || ''
                ].join(' ');
              }).join(separator);
              return markup + separator + links;
            })()
        """.trimIndent()

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}
