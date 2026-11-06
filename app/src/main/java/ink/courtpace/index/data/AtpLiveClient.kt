package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpEvents
import ink.courtpace.index.core.live.AtpMatchLinks
import ink.courtpace.index.core.live.AtpResultsParser
import ink.courtpace.index.core.live.AtpStatsParser
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Tournament
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads completed singles matches from ATP pages on the device and computes the live ace rating.
 *
 * ATP blocks plain HTTP clients for these pages. The on-device WebView is used for the current
 * scores hub, tournament results links and JavaScript-rendered match stats; no guessed match-ID
 * range or third-party API key is required.
 */
class AtpLiveClient(context: Context) {

    private val web = AtpWebReader(context)
    private val store = MatchStatsStore(context)

    /** Discover every currently listed ATP 1000/500/250 event from the ATP current-scores hub. */
    suspend fun discoverEvents(
        tournaments: List<Tournament>,
        fallback: List<LiveEvent>,
        year: Int,
    ): AtpEvents.Discovery {
        val source = web.readPageSource(CURRENT_SCORES_URL)
        val html = source.orEmpty()
        val discovered = AtpEvents.fromCurrentScoresPage(html, tournaments, year)
        val events = discovered.ifEmpty { AtpEvents.fallback(fallback, year) }
        return AtpEvents.Discovery(
            events = events,
            pageBytes = html.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * Rates the event from completed singles match stats linked by its own results page.
     *
     * The previous implementation substituted ms001..ms096 when discovery failed. Those IDs
     * are not a draw-order index: most are nonexistent, and each miss cost another WebView.
     * An unreadable results page now produces an explicit diagnostic and zero match-page scans.
     */
    suspend fun fetchAceRating(
        event: AtpEvents.Ref,
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)? = null,
    ): Result<AceRating.Result> {
        return try {
            Result.success(fetchAceRatingInternal(event, onProgress, onDiscovery))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchAceRatingInternal(
        event: AtpEvents.Ref,
        onProgress: (found: Int, tried: Int) -> Unit,
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)?,
    ): AceRating.Result {
        val eventKey = "${event.atpId}-${event.year}"
        val cached = store.readAll(eventKey)
        val collected = ConcurrentHashMap(cached)
        val tried = AtomicInteger(0)
        val withContent = AtomicInteger(0)
        val routeAttempts = AtomicInteger(0)
        val blocked = AtomicBoolean(false)
        val samples = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val gate = Semaphore(PARALLEL_PAGES)

        val resultsUrl =
            "https://www.atptour.com/en/scores/current/${event.slug}/${event.atpId}/results"
        val source = web.readPageSource(resultsUrl, RESULTS_RENDER_MS).orEmpty()
        // The results page is authoritative for completion. ATP has emitted both the
        // /archive and /live stats-centre routes for finished matches during an active event.
        // Keep the discovered route and try its sibling route only if it yields no usable stats.
        val links = AtpMatchLinks.singlesLinks(
            AtpMatchLinks.linksFromResultsPage(source),
        ).filter { link ->
            link.atpId == event.atpId && link.year == event.year
        }.distinctBy { it.id }
        val pageBytes = source.toByteArray(Charsets.UTF_8).size
        onDiscovery?.invoke(links.size, pageBytes)

        if (links.isEmpty()) {
            val totalStatsLinks = AtpMatchLinks.linksFromResultsPage(source)
                .count { it.atpId == event.atpId && it.year == event.year }
            error(
                "no completed singles stats links on the results page " +
                    "($pageBytes bytes, $totalStatsLinks stats links found); no match pages scanned",
            )
        }

        withTimeoutOrNull(WALK_BUDGET_MS) {
            coroutineScope {
                links.map { link ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            if (blocked.get()) return@withPermit
                            if (collected.containsKey(link.id)) {
                                onProgress(collected.size, tried.incrementAndGet())
                                return@withPermit
                            }

                            val attempt = tried.incrementAndGet()
                            onProgress(collected.size, attempt)

                            var parsed: ink.courtpace.index.core.live.AtpMatchStats? = null
                            for ((variantIndex, variant) in AtpMatchLinks.routeVariants(link).withIndex()) {
                                routeAttempts.incrementAndGet()
                                val rendered = web.read(
                                    statsUrl(variant),
                                    timeoutMs = if (variantIndex == 0) MATCH_RENDER_MS else FALLBACK_RENDER_MS,
                                    // Do not return as soon as the ace labels appear: ATP
                                    // renders the serving denominators progressively too.
                                    isReady = { AtpStatsParser.parse(it) != null },
                                    returnLastOnTimeout = true,
                                )
                                if (rendered != null && rendered.contains(AtpWebReader.BLOCKED, true)) {
                                    blocked.set(true)
                                    break
                                }
                                val bodyText = rendered?.substringBefore("\n[ATP_WEBVIEW]").orEmpty()
                                if (bodyText.isNotBlank()) withContent.incrementAndGet()
                                parsed = rendered?.let { snapshot ->
                                    AtpStatsParser.parse(snapshot)
                                        ?: AtpStatsParser.parse(AtpResultsParser.toText(snapshot))
                                }
                                if (parsed != null) break

                                if (rendered != null && samples.size < MAX_DIAGNOSTIC_SAMPLES) {
                                    val preview = rendered.replace(Regex("\\s+"), " ")
                                        .take(DIAGNOSTIC_PREVIEW_CHARS)
                                    samples.add(
                                        "${variant.section}/${variant.id}: ${rendered.length} chars · $preview",
                                    )
                                }
                            }
                            if (parsed != null) collected[link.id] = parsed
                            onProgress(collected.size, tried.get())
                        }
                    }
                }.awaitAll()
            }
        }

        // Persist every successful read, even when a sibling page is blocked or the walk budget
        // expires. The next refresh can then request only the still-missing match IDs instead of
        // downloading already-read pages again.
        store.writeAll(eventKey, collected)
        val found = collected.values.toList()
        return AceRating.rate(found) ?: error(
            when {
                found.isNotEmpty() ->
                    "only ${found.size} completed matches have stats so far " +
                        "(${links.size} completed singles links on the results page)"
                blocked.get() -> "ATP blocked a match-stats page on this device"
                withContent.get() == 0 -> {
                    val sample = samples.peek()?.let { " · page sample: $it" }.orEmpty()
                    "no readable stats-page body from ${links.size} matches after " +
                        "${routeAttempts.get()} archive/live route attempts$sample"
                }
                else -> {
                    val sample = samples.peek()?.let { " · sample: $it" }.orEmpty()
                    "read ${withContent.get()} stats-page bodies but could not recognise " +
                        "the ace/serve fields$sample"
                }
            },
        )
    }

    private fun statsUrl(link: AtpMatchLinks.MatchLink): String =
        "https://www.atptour.com/en/scores/match-stats/${link.section}/" +
            "${link.year}/${link.atpId}/${link.id}"

    companion object {
        const val CURRENT_SCORES_URL = "https://www.atptour.com/en/scores/current"

        /** The event/results page is allowed to render fully before links are collected. */
        const val RESULTS_RENDER_MS = 45_000L

        /** A single match page only needs to wait for its stats to appear. */
        const val MATCH_RENDER_MS = 15_000L
        const val FALLBACK_RENDER_MS = 8_000L
        const val MIN_READABLE_PAGE_CHARS = 400
        const val MAX_DIAGNOSTIC_SAMPLES = 2
        const val DIAGNOSTIC_PREVIEW_CHARS = 100

        /** Small bounded WebView pool; large parallel batches are killed by Android. */
        const val PARALLEL_PAGES = 4

        /** Keep whatever has been collected when this internal budget expires. */
        const val WALK_BUDGET_MS = 600_000L
    }
}
