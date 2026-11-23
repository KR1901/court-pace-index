package ink.courtpace.index.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.h2h.H2HAnalytics
import ink.courtpace.index.core.h2h.H2HComparison
import ink.courtpace.index.core.h2h.H2HMatch
import ink.courtpace.index.core.h2h.H2HPlayer
import ink.courtpace.index.core.h2h.H2HPlayerProfile
import ink.courtpace.index.core.h2h.H2HRecord
import ink.courtpace.index.core.h2h.H2HRepository
import ink.courtpace.index.core.h2h.H2HSurfaceFilter
import ink.courtpace.index.core.playerstats.PlayerStatsData
import ink.courtpace.index.core.playerstats.PlayerStatsLiveUpdater
import ink.courtpace.index.core.playerstats.PlayerStatsRepository
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun H2HHost() {
    val context = LocalContext.current
    var asset by remember { mutableStateOf<PlayerStatsData?>(null) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var loadingStats by remember { mutableStateOf(true) }
    val liveState by PlayerStatsLiveUpdater.state.collectAsState()

    LaunchedEffect(context) {
        try {
            val alreadyLoaded = PlayerStatsLiveUpdater.state.value?.data
            val initial = alreadyLoaded ?: withContext(Dispatchers.IO) {
                PlayerStatsRepository.load(context)
            }
            asset = initial
            statsError = null
            PlayerStatsLiveUpdater.publishInitial(initial)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            statsError = failure.message ?: "The Player Stats archive could not be read."
        } finally {
            loadingStats = false
        }
    }

    LaunchedEffect(liveState) {
        liveState?.let { state ->
            asset = state.data
            statsError = null
        }
    }

    if (loadingStats && asset == null) {
        val colors = PaceTheme.colors
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
            Spacer(Modifier.height(12.dp))
            Text("Preparing player records…", color = colors.textDim)
        }
    } else {
        H2HScreen(asset = asset, playerStatsError = statsError)
    }
}

@Composable
fun H2HScreen(
    asset: PlayerStatsData? = null,
    playerStatsError: String? = null,
) {
    val colors = PaceTheme.colors
    val currentYear = asset?.meta?.lastSeason?.takeIf { it > 0 } ?: LocalDate.now().year
    var first by remember { mutableStateOf<H2HPlayer?>(null) }
    var second by remember { mutableStateOf<H2HPlayer?>(null) }
    var comparison by remember { mutableStateOf<H2HComparison?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ytdSurface by remember { mutableStateOf(H2HSurfaceFilter.ALL) }
    var careerSurface by remember { mutableStateOf(H2HSurfaceFilter.ALL) }

    LaunchedEffect(first?.id, second?.id) {
        comparison = null
        error = null
        val playerA = first
        val playerB = second
        if (playerA == null || playerB == null || playerA.id == playerB.id) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            comparison = H2HRepository.loadComparison(playerA, playerB)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load the head-to-head data."
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = LocalTabBarInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ATP TOUR · PLAYER COMPARISON",
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    ),
                    color = colors.textFaint,
                )
                Text(
                    text = "H2H",
                    style = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                    color = colors.text,
                )
                Text(
                    text = "Compare rivalries, results and career records",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.textDim,
                )
            }
        }

        item {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                elevation = 12.dp,
            ) {
                SectionLabel("Choose two players")
                Spacer(Modifier.height(10.dp))
                H2HPlayerPicker(
                    label = "Player 1",
                    selected = first,
                    otherPlayerId = second?.id,
                    onSelected = { first = it },
                )
                Spacer(Modifier.height(10.dp))
                H2HPlayerPicker(
                    label = "Player 2",
                    selected = second,
                    otherPlayerId = first?.id,
                    onSelected = { second = it },
                )
                if (first != null && second != null && first?.id == second?.id) {
                    GlassNote("Select two different players to compare their records.")
                }
            }
        }

        if (first == null || second == null) {
            item {
                GlassNote("Search for two ATP players above. Their head-to-head history and player records will appear here.")
            }
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading player records and meetings…", color = colors.textDim)
                }
            }
        }

        playerStatsError?.let { message ->
            item { GlassNote("Player W/L archive unavailable: $message. H2H meetings and player facts can still be shown.") }
        }
        if (asset == null && playerStatsError == null && comparison != null) {
            item { GlassNote("Player W/L archive is unavailable for these selections; match-level H2H data remains available.") }
        }
        error?.let { message ->
            item { GlassNote("H2H data could not be loaded: $message") }
        }

        val loaded = comparison
        if (loaded != null) {
            item {
                H2HScoreCard(
                    first = loaded.player1,
                    second = loaded.player2,
                    meetings = loaded.meetings,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayerFactsCard(
                        modifier = Modifier.weight(1f),
                        profile = loaded.player1,
                    )
                    PlayerFactsCard(
                        modifier = Modifier.weight(1f),
                        profile = loaded.player2,
                    )
                }
            }

            item {
                val firstRecord = H2HAnalytics.record(
                    playerId = loaded.player1.player.id,
                    playerStats = asset?.players?.firstOrNull { it.id == loaded.player1.player.id },
                    matches = loaded.player1.matches,
                    year = currentYear,
                    surface = ytdSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                )
                val secondRecord = H2HAnalytics.record(
                    playerId = loaded.player2.player.id,
                    playerStats = asset?.players?.firstOrNull { it.id == loaded.player2.player.id },
                    matches = loaded.player2.matches,
                    year = currentYear,
                    surface = ytdSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                )
                val firstSetRecord = H2HAnalytics.setRecord(
                    playerId = loaded.player1.player.id,
                    meetings = loaded.player1.matches,
                    year = currentYear,
                    surface = ytdSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                    eligibleTourMatchesOnly = true,
                )
                val secondSetRecord = H2HAnalytics.setRecord(
                    playerId = loaded.player2.player.id,
                    meetings = loaded.player2.matches,
                    year = currentYear,
                    surface = ytdSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                    eligibleTourMatchesOnly = true,
                )
                RecordSection(
                    title = "YEAR-TO-DATE · $currentYear",
                    surface = ytdSurface,
                    onSurface = { ytdSurface = it },
                    firstName = loaded.player1.player.name,
                    firstRecord = firstRecord,
                    firstSetRecord = firstSetRecord,
                    secondName = loaded.player2.player.name,
                    secondRecord = secondRecord,
                    secondSetRecord = secondSetRecord,
                )
            }

            item {
                val firstRecord = H2HAnalytics.record(
                    playerId = loaded.player1.player.id,
                    playerStats = asset?.players?.firstOrNull { it.id == loaded.player1.player.id },
                    matches = loaded.player1.matches,
                    year = null,
                    surface = careerSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                )
                val secondRecord = H2HAnalytics.record(
                    playerId = loaded.player2.player.id,
                    playerStats = asset?.players?.firstOrNull { it.id == loaded.player2.player.id },
                    matches = loaded.player2.matches,
                    year = null,
                    surface = careerSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                )
                val firstSetRecord = H2HAnalytics.setRecord(
                    playerId = loaded.player1.player.id,
                    meetings = loaded.player1.matches,
                    year = null,
                    surface = careerSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                    eligibleTourMatchesOnly = true,
                )
                val secondSetRecord = H2HAnalytics.setRecord(
                    playerId = loaded.player2.player.id,
                    meetings = loaded.player2.matches,
                    year = null,
                    surface = careerSurface,
                    tournamentIndoorById = asset?.meta?.tournamentIndoorById.orEmpty(),
                    eligibleTourMatchesOnly = true,
                )
                RecordSection(
                    title = "CAREER",
                    surface = careerSurface,
                    onSurface = { careerSurface = it },
                    firstName = loaded.player1.player.name,
                    firstRecord = firstRecord,
                    firstSetRecord = firstSetRecord,
                    secondName = loaded.player2.player.name,
                    secondRecord = secondRecord,
                    secondSetRecord = secondSetRecord,
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("H2H EVENT BREAKDOWN")
                    Text(
                        text = "${loaded.meetings.size} recorded meetings · newest first",
                        style = TextStyle(fontSize = 12.sp),
                        color = colors.textDim,
                    )
                }
            }

            if (loaded.meetings.isEmpty()) {
                item { GlassNote("No recorded ATP meetings were found for this pair.") }
            } else {
                items(loaded.meetings) { match ->
                    H2HMeetingRow(
                        match = match,
                        firstPlayerId = loaded.player1.player.id,
                    )
                }
            }

            item {
                Text(
                    text = "Match results, player profiles and rankings: TennisMyLife · " +
                        "retrieved ${H2HAnalytics.formatDate(loaded.fetchedAt)}",
                    style = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp),
                    color = colors.textFaint,
                )
            }
        }
    }
}

@Composable
private fun H2HPlayerPicker(
    label: String,
    selected: H2HPlayer?,
    otherPlayerId: String?,
    onSelected: (H2HPlayer?) -> Unit,
) {
    val colors = PaceTheme.colors
    var query by remember { mutableStateOf(selected?.name.orEmpty()) }
    var suggestions by remember { mutableStateOf<List<H2HPlayer>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }

    LaunchedEffect(query, selected?.id) {
        val cleaned = query.trim()
        if (cleaned.length < 2 || cleaned.equals(selected?.name, ignoreCase = true)) {
            suggestions = emptyList()
            searching = false
            searchError = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        searchError = false
        try {
            suggestions = H2HRepository.searchPlayers(cleaned)
                .filter { it.id != otherPlayerId }
            searchError = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            suggestions = emptyList()
            searchError = true
        } finally {
            searching = false
        }
    }

    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            if (selected != null) onSelected(null)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("Search ATP player") },
    )

    if (searching) {
        Text(
            text = "Searching players…",
            style = TextStyle(fontSize = 11.sp),
            color = colors.textFaint,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
    }
    if (searchError) {
        Text(
            text = "Player search is temporarily unavailable.",
            style = TextStyle(fontSize = 11.sp),
            color = colors.textDim,
        )
    }
    if (suggestions.isNotEmpty()) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(6.dp),
            elevation = 6.dp,
        ) {
            suggestions.forEach { player ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            query = player.name
                            suggestions = emptyList()
                            onSelected(player)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = player.name,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                    )
                    player.country?.let {
                        Text(
                            text = it,
                            style = TextStyle(fontSize = 10.5.sp),
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun H2HScoreCard(
    first: H2HPlayerProfile,
    second: H2HPlayerProfile,
    meetings: List<H2HMatch>,
) {
    val colors = PaceTheme.colors
    val firstWins = H2HAnalytics.winsAgainst(first.player.id, meetings)
    val secondWins = H2HAnalytics.winsAgainst(second.player.id, meetings)
    val firstSets = H2HAnalytics.setRecord(first.player.id, meetings)
    val secondSets = H2HAnalytics.setRecord(second.player.id, meetings)
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        elevation = 16.dp,
    ) {
        SectionLabel("HEAD-TO-HEAD MATCH WINS")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = first.player.name,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = firstWins.toString(),
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                    color = colors.accent,
                )
            }
            Text(
                text = "–",
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light),
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = second.player.name,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondWins.toString(),
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                    color = colors.accent,
                )
            }
        }
        HairLine(Modifier.padding(vertical = 10.dp))
        SectionLabel("SET & TIEBREAK RECORD")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            H2HSetSummary(
                modifier = Modifier.weight(1f),
                record = firstSets,
                alignEnd = false,
            )
            H2HSetSummary(
                modifier = Modifier.weight(1f),
                record = secondSets,
                alignEnd = true,
            )
        }
        Text(
            text = "${meetings.size} recorded meetings · set scores available in ${firstSets.matchesWithScores}",
            style = TextStyle(fontSize = 11.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun H2HSetSummary(
    modifier: Modifier,
    record: ink.courtpace.index.core.h2h.H2HSetRecord,
    alignEnd: Boolean,
) {
    val colors = PaceTheme.colors
    val setRecord = if (record.setsWon == null || record.setsLost == null) {
        "—"
    } else {
        "${record.setsWon}–${record.setsLost}"
    }
    val winPercentage = record.winPercentage?.let {
        String.format(Locale.ROOT, "%.1f%%", it)
    } ?: "—"
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "SET W/L",
            style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp),
            color = colors.textFaint,
        )
        Text(
            text = setRecord,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = colors.accent,
        )
        Text(
            text = "Set Win% $winPercentage",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        val tiebreakRecord = if (record.tiebreaksWon == null || record.tiebreaksLost == null) {
            "—"
        } else {
            "${record.tiebreaksWon}–${record.tiebreaksLost}"
        }
        Text(
            text = "Tiebreak W/L $tiebreakRecord",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
    }
}

@Composable
private fun PlayerFactsCard(
    modifier: Modifier,
    profile: H2HPlayerProfile,
) {
    val colors = PaceTheme.colors
    val age = H2HAnalytics.age(profile.birthDate)
    GlassSurface(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        elevation = 8.dp,
    ) {
        Text(
            text = profile.player.name,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        FactLine(
            "Current rank",
            profile.currentRank?.let { "#$it" } ?: if (profile.rankingAvailable) "Unranked" else "Unavailable",
        )
        FactLine("Age", age?.let { "$it years" } ?: "Unavailable")
        FactLine("Height", H2HAnalytics.formatHeight(profile.heightCm))
        profile.rankingDate?.let {
            Text(
                text = "Rank as of ${H2HAnalytics.formatDate(it)}",
                style = TextStyle(fontSize = 9.5.sp),
                color = colors.textFaint,
            )
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    val colors = PaceTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 9.5.sp), color = colors.textFaint)
        Text(
            value,
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecordSection(
    title: String,
    surface: H2HSurfaceFilter,
    onSurface: (H2HSurfaceFilter) -> Unit,
    firstName: String,
    firstRecord: H2HRecord,
    firstSetRecord: ink.courtpace.index.core.h2h.H2HSetRecord,
    secondName: String,
    secondRecord: H2HRecord,
    secondSetRecord: ink.courtpace.index.core.h2h.H2HSetRecord,
) {
    val colors = PaceTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(title)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            H2HSurfaceFilter.entries.forEach { option ->
                GlassChip(
                    text = option.label,
                    selected = surface == option,
                    onClick = { onSurface(option) },
                )
            }
        }
        GlassNote(
            "Set W/L counts completed sets; Set Win% is sets won ÷ sets played. Tiebreak W/L counts completed 7–6 and match tiebreaks won–lost. These score-based stats follow the selected year and surface. Hard includes unknown venue settings; Indoor/Outdoor Hard require explicit venue classification."
        )
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            elevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RecordPlayerColumn(
                    modifier = Modifier.weight(1f),
                    name = firstName,
                    record = firstRecord,
                    setRecord = firstSetRecord,
                )
                RecordPlayerColumn(
                    modifier = Modifier.weight(1f),
                    name = secondName,
                    record = secondRecord,
                    setRecord = secondSetRecord,
                )
            }
            HairLine(Modifier.padding(vertical = 9.dp))
            Text(
                text = if (firstRecord.matches == null || secondRecord.matches == null) {
                    "W/L archive coverage unavailable for one or both players"
                } else {
                    "${firstRecord.matches} and ${secondRecord.matches} matches in this view"
                },
                style = TextStyle(fontSize = 10.5.sp),
                color = colors.textFaint,
            )
        }
    }
}

@Composable
private fun RecordPlayerColumn(
    modifier: Modifier,
    name: String,
    record: H2HRecord,
    setRecord: ink.courtpace.index.core.h2h.H2HSetRecord,
) {
    val colors = PaceTheme.colors
    val winPercentage = record.winPercentage?.let {
        String.format(Locale.ROOT, "%.1f%%", it)
    } ?: "—"
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = name,
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (record.wins == null || record.losses == null) "—" else "${record.wins}–${record.losses}",
            style = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold),
            color = colors.accent,
        )
        Text(
            text = "Win% $winPercentage",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        Text(
            text = "${record.titles} titles",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        val setWl = if (setRecord.setsWon == null || setRecord.setsLost == null) {
            "—"
        } else {
            "${setRecord.setsWon}–${setRecord.setsLost}"
        }
        val setWinPct = setRecord.winPercentage?.let {
            String.format(Locale.ROOT, "%.1f%%", it)
        } ?: "—"
        val tbWl = if (setRecord.tiebreaksWon == null || setRecord.tiebreaksLost == null) {
            "—"
        } else {
            "${setRecord.tiebreaksWon}–${setRecord.tiebreaksLost}"
        }
        Text(
            text = "Set W/L $setWl",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        Text(
            text = "Set Win% $setWinPct",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        Text(
            text = "Tiebreak W/L $tbWl",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
    }
}

@Composable
private fun H2HMeetingRow(
    match: H2HMatch,
    firstPlayerId: String,
) {
    val colors = PaceTheme.colors
    val firstWon = match.winnerId == firstPlayerId
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
        elevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = match.eventName,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "TOURNAMENT DATE",
                    style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
                    color = colors.textFaint,
                )
                Text(
                    text = H2HAnalytics.formatDate(match.date),
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                    color = colors.textDim,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "${match.winnerName} def. ${match.loserName}",
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
            color = if (firstWon) colors.accent else colors.text,
        )
        Text(
            text = match.score?.ifBlank { null } ?: "Score unavailable",
            style = TextStyle(fontSize = 11.sp),
            color = colors.textDim,
        )
        val details = listOfNotNull(
            match.round?.takeIf { it.isNotBlank() },
            match.surface?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = TextStyle(fontSize = 9.5.sp),
                color = colors.textFaint,
            )
        }
    }
}
