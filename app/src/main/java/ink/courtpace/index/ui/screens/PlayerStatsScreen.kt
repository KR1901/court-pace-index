package ink.courtpace.index.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.playerstats.HeightGroupCohort
import ink.courtpace.index.core.playerstats.HeightGroupSummary
import ink.courtpace.index.core.playerstats.PlayerRankingMetric
import ink.courtpace.index.core.playerstats.PlayerRankingRow
import ink.courtpace.index.core.playerstats.PlayerStatsAnalytics
import ink.courtpace.index.core.playerstats.PlayerStatsData
import ink.courtpace.index.core.playerstats.PlayerStatsDateTime
import ink.courtpace.index.core.playerstats.PlayerStatsRepository
import ink.courtpace.index.core.playerstats.PlayerStatsLiveUpdater
import ink.courtpace.index.core.playerstats.PlayerSurfaceFilter
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun PlayerStatsHost() {
    val context = LocalContext.current
    var asset by remember { mutableStateOf<PlayerStatsData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var liveStatus by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val sharedLiveState by PlayerStatsLiveUpdater.state.collectAsState()

    LaunchedEffect(context) {
        loading = true
        error = null
        val initial = withContext(Dispatchers.IO) {
            runCatching { PlayerStatsRepository.load(context) }
        }
        initial.onSuccess {
            asset = it
            PlayerStatsLiveUpdater.publishInitial(it)
        }.onFailure { error = it.message ?: "Could not read the player statistics asset." }
        loading = false
    }

    LaunchedEffect(sharedLiveState) {
        sharedLiveState?.let { live ->
            asset = live.data
            liveStatus = live.checkedAt?.let {
                "Checked " + (PlayerStatsDateTime.formatTimestamp(it) ?: "time unavailable") +
                    " · +" + live.addedMatches + " results · " + live.enrichedMatches + " stats enriched"
            } ?: "Automatic sync scheduled"
            error = live.error
        }
    }

    when {
        asset != null -> PlayerStatsScreen(
            asset = asset!!,
            liveStatus = liveStatus,
            liveError = error,
        )
        loading -> PlayerStatsLoading()
        else -> PlayerStatsError(message = error ?: "Player statistics are unavailable.")
    }
}

@Composable
fun PlayerStatsScreen(
    asset: PlayerStatsData,
    liveStatus: String? = null,
    liveError: String? = null,
) {
    val colors = PaceTheme.colors
    val years = remember(asset) {
        asset.players.asSequence()
            .flatMap { it.seasons.asSequence() }
            .map { it.y }
            .distinct()
            .sortedDescending()
            .toList()
    }
    var year by remember(asset) {
        mutableIntStateOf(years.firstOrNull() ?: asset.meta.lastSeason)
    }
    var mode by remember { mutableStateOf(PlayerStatsMode.RANKINGS) }
    var metric by remember { mutableStateOf(PlayerRankingMetric.HOLD) }
    var heightCohort by remember { mutableStateOf(HeightGroupCohort.TOP_100) }
    var surface by remember { mutableStateOf(PlayerSurfaceFilter.ALL) }
    var minimumGames by remember { mutableIntStateOf(100) }
    var search by remember { mutableStateOf("") }
    var yearMenu by remember { mutableStateOf(false) }
    var sampleMenu by remember { mutableStateOf(false) }

    val rankingRows = remember(asset, year, surface, metric, minimumGames) {
        PlayerStatsAnalytics.rankings(asset, year, surface, metric, minimumGames)
    }
    val visibleRows = remember(rankingRows, search) {
        val query = search.trim().lowercase()
        if (query.isEmpty()) {
            rankingRows.filter { it.matches > 0 || it.score != null }
        } else {
            rankingRows.filter {
                it.player.name.lowercase().contains(query) ||
                    (it.player.country?.lowercase()?.contains(query) == true) ||
                    it.player.id.lowercase().contains(query)
            }
        }
    }
    val heightResult = remember(asset, year, surface, heightCohort) {
        PlayerStatsAnalytics.heightGroups(asset, year, surface, heightCohort)
    }
    val yearSnapshot = remember(asset, year) {
        asset.meta.top100SeasonSnapshots.firstOrNull { it.year == year }
    }
    val expandedYearSnapshot = remember(asset, year) {
        asset.meta.top1000SeasonSnapshots.firstOrNull { it.year == year }
    }
    val cohortLabel = if (year == asset.meta.lastSeason) {
        "live " + heightCohort.label + " players"
    } else {
        "year-end " + heightCohort.label + " players"
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
                    text = "ATP TOUR · PLAYER ANALYTICS",
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
                    color = colors.textFaint,
                )
                Text(
                    text = "Player Stats",
                    style = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                    color = colors.text,
                )
                Text(
                    text = "Annual serve, return and height analysis",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.textDim,
                )
                Text(
                    text = "ARCHIVE BASELINE · " +
                        (PlayerStatsDateTime.formatDate(asset.meta.generated) ?: "—"),
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                    color = colors.textFaint,
                )
                Text(
                    text = "LIVE SYNC · " + (liveStatus ?: "Preparing automatic updates"),
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    color = colors.textDim,
                )
                val lastUpdatedLabel = asset.meta.liveUpdatedAt
                    ?.let { PlayerStatsDateTime.formatTimestamp(it, includeSeconds = false) }
                    ?.let { " · last updated " + it }
                    .orEmpty()
                Text(
                    text = asset.meta.liveMatchCount.toString() + " new results · " +
                        asset.meta.liveEnrichedMatchCount + " later stat enrichments since archive snapshot" +
                        lastUpdatedLabel,
                    style = TextStyle(fontSize = 10.sp),
                    color = colors.textFaint,
                )
                if (liveError != null) {
                    GlassNote(
                        text = "Live sync issue: " + liveError + " · saved statistics remain available and the app will retry automatically.",
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(
                    text = "Rankings",
                    selected = mode == PlayerStatsMode.RANKINGS,
                    onClick = { mode = PlayerStatsMode.RANKINGS },
                )
                GlassChip(
                    text = "Height groups",
                    selected = mode == PlayerStatsMode.HEIGHT_GROUPS,
                    onClick = { mode = PlayerStatsMode.HEIGHT_GROUPS },
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    GlassChip(
                        text = "YEAR · " + year,
                        selected = true,
                        onClick = { yearMenu = true },
                    )
                    DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                        years.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.toString()) },
                                onClick = { year = option; yearMenu = false },
                            )
                        }
                    }
                }
                if (mode == PlayerStatsMode.RANKINGS) {
                    Box {
                        GlassChip(
                            text = "SAMPLE · " + minimumGames + " GAMES",
                            selected = minimumGames > 0,
                            onClick = { sampleMenu = true },
                        )
                        DropdownMenu(expanded = sampleMenu, onDismissRequest = { sampleMenu = false }) {
                            listOf(0, 50, 100, 250).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(if (option == 0) "No minimum" else option.toString() + " games") },
                                    onClick = { minimumGames = option; sampleMenu = false },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (year !in asset.meta.statsAvailableSeasons) {
            item {
                GlassNote(
                    text = "No eligible service/return match-stat rows are available for " + year + ". Match results may still be present, but Hold% and Break% cannot be calculated for this season.",
                )
            }
        }

        if (mode == PlayerStatsMode.HEIGHT_GROUPS) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(text = "Ranking cohort")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        HeightGroupCohort.entries.forEach { option ->
                            GlassChip(
                                text = option.label,
                                selected = heightCohort == option,
                                onClick = { heightCohort = option },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionLabel(text = "Surface")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlayerSurfaceFilter.entries.forEach { option ->
                    GlassChip(
                        text = option.label,
                        selected = surface == option,
                        onClick = { surface = option },
                    )
                }
            }
        }

        if (mode == PlayerStatsMode.RANKINGS) {
            item {
                SectionLabel(text = "Ranking metric")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PlayerRankingMetric.entries.forEach { option ->
                        GlassChip(
                            text = option.label,
                            selected = metric == option,
                            onClick = { metric = option },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("Search all players") },
                    placeholder = { Text("Player name, country or ID") },
                    textStyle = TextStyle(fontSize = 14.sp),
                )
            }

            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = if (search.isBlank()) "Qualified leaderboard" else "Player search",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                    )
                    Text(
                        text = if (search.isBlank()) {
                            "All players with archive results · " + visibleRows.size + " shown of " + asset.players.size
                        } else {
                            visibleRows.size.toString() + " matching players · search covers the full archive"
                        },
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textDim,
                    )
                }
            }

            if (visibleRows.isEmpty()) {
                item {
                    EmptyPlayerStats(
                        text = if (search.isBlank()) {
                            "No eligible player rows for this year and surface."
                        } else {
                            "No players match this search."
                        },
                    )
                }
            } else {
                items(visibleRows, key = { it.player.id }) { row ->
                    PlayerRankingItem(row = row, metric = metric)
                }
            }

            item {
                GlassNote(
                    text = "Every player with an archive result for the selected year and surface is included. Qualified players receive ranks; those below " + minimumGames + " relevant games remain visible but are labelled low sample and left unranked. Combined is H+B: Hold% plus Break%.",
                )
            }
        } else {
            val rankedGroups = heightResult.groups.sortedWith(
                compareByDescending<HeightGroupSummary> { it.combined ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.id },
            )
            val snapshotDateValue = expandedYearSnapshot?.asOf ?: yearSnapshot?.asOf
            val snapshotDate = snapshotDateValue?.let {
                PlayerStatsDateTime.formatDate(it) ?: it
            }
            val snapshotProvider = if (heightCohort == HeightGroupCohort.TOP_100) {
                "TML"
            } else {
                expandedYearSnapshot?.provider ?: "TML"
            }
            val unresolvedNote = heightResult.unresolvedRankRows
                .takeIf { it > 0 }
                ?.let { count ->
                    " " + count + " ranking row(s) in the selected cohort have no stable player ID and are excluded from player-level height calculations."
                }.orEmpty()
            val coverageNote = if (heightResult.availableThroughRank > 0) {
                " The dated ranking snapshot includes published positions through #" +
                    heightResult.availableThroughRank + "."
            } else {
                ""
            }
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = if (year == asset.meta.lastSeason) {
                            "Current ATP " + heightCohort.label + " · " + snapshotProvider
                        } else {
                            year.toString() + " ATP year-end " + heightCohort.label + " · " + snapshotProvider
                        },
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                    )
                    val snapshotDescription = when {
                        year == asset.meta.lastSeason && heightCohort != HeightGroupCohort.TOP_100 ->
                            "TML Top-100 cross-check · " + (snapshotDate ?: "date unavailable")
                        snapshotDate != null ->
                            snapshotProvider + " ranking snapshot · " + snapshotDate
                        else -> "No ATP ranking snapshot available for " + year
                    }
                    Text(
                        text = snapshotDescription +
                            " · " + heightResult.cohortSize + " identified players" +
                            (heightResult.unresolvedRankRows.takeIf { it > 0 }
                                ?.let { " · " + it + " unresolved" } ?: ""),
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textDim,
                    )
                    Text(
                        text = heightResult.heightKnown.toString() + " heights known · " + heightResult.unassignedHeight + " unassigned",
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textDim,
                    )
                    HairLine()
                    GlassNote(
                        text = when {
                            !heightResult.cohortAvailable ->
                                "The " + snapshotProvider + " snapshot for " + year + " only covers ranks through #" +
                                    heightResult.availableThroughRank + "; a complete " + heightCohort.label +
                                    " cohort is not available. No smaller cohort is substituted." +
                                    coverageNote
                            yearSnapshot == null && expandedYearSnapshot == null ->
                                "No dated ATP ranking snapshot is available for " + year + ". No cohort is substituted."
                            year == asset.meta.lastSeason ->
                                "The live season uses the latest available official ATP " + heightCohort.label +
                                    " ranking list for " + year + " from " + snapshotProvider +
                                    ". Group performance uses match statistics from " + year + "."
                            else ->
                                "The cohort is the " + heightCohort.label + " from the final ATP ranking snapshot published in " + year + " (" +
                                    (snapshotDate ?: snapshotDateValue.orEmpty()) +
                                    ") using " + snapshotProvider + ". Group performance uses match statistics from that same calendar year."
                        } + unresolvedNote,
                    )
                }
            }

            if (heightResult.cohortAvailable) {
                items(rankedGroups, key = { it.id }) { group ->
                    HeightGroupCard(
                        group = group,
                        rank = rankedGroups.indexOf(group) + 1,
                        cohortLabel = cohortLabel,
                    )
                }
                item {
                    GlassNote(
                        text = "Group percentages are pooled from all eligible games in the group, not averaged across player percentages. Results show completed ATP tour-level singles matches. Missing heights are not imputed.",
                    )
                }
            } else {
                item {
                    EmptyPlayerStats(
                        text = "The selected " + heightCohort.label + " cohort is not present in this year's ranking snapshot. Choose a smaller cohort or another year with sufficient ranking coverage.",
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "SOURCE · " + asset.meta.source + " · PERSONAL NON-COMMERCIAL RESEARCH",
                style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
                color = colors.textFaint,
            )
            Text(
                text = "Calendar years · automatic foreground checks about every 15 seconds · Android background checks at 15-minute intervals or later · source publication may lag",
                style = TextStyle(fontSize = 10.5.sp),
                color = colors.textFaint,
            )
        }
    }
}

private enum class PlayerStatsMode { RANKINGS, HEIGHT_GROUPS }

@Composable
private fun PlayerRankingItem(
    row: PlayerRankingRow,
    metric: PlayerRankingMetric,
) {
    val colors = PaceTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.rank?.let { "#" + it } ?: "—",
                modifier = Modifier.width(42.dp),
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = if (row.qualified) colors.accent else colors.textFaint,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.player.name,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val height = PlayerStatsAnalytics.heightText(row.player.heightCm)
                val top100 = row.player.currentRank?.let { " · Current #" + it } ?: ""
                Text(
                    text = (row.player.country ?: "—") + " · " + height + top100,
                    style = TextStyle(fontSize = 10.5.sp),
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = row.score?.let { formatMetric(it, metric) } ?: "—",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    color = colors.text,
                )
                Text(
                    text = if (row.qualified) metric.label else "Low sample",
                    style = TextStyle(fontSize = 9.5.sp),
                    color = colors.textFaint,
                )
            }
        }
        if (row.holdPct != null || row.breakPct != null) {
            Text(
                text = "Hold " + formatPct(row.holdPct) + "  ·  Break " + formatPct(row.breakPct) + "  ·  H+B " + (row.combined?.let { formatNumber(it) } ?: "—"),
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = colors.textDim,
            )
        } else {
            Text("No service/return statistics for this year and surface", color = colors.textFaint, fontSize = 11.sp)
        }
        Text(
            text = "S " + row.serviceGames + " games  ·  R " + row.returnGames + " games  ·  " + row.wins + "–" + row.losses + " W–L  ·  " + row.matches + " matches",
            style = TextStyle(fontSize = 10.sp),
            color = colors.textFaint,
        )
        HairLine()
    }
}

@Composable
private fun HeightGroupCard(
    group: HeightGroupSummary,
    rank: Int,
    cohortLabel: String,
) {
    val colors = PaceTheme.colors
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rank.toString(),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    color = colors.text,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = group.label,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                )
                Text(
                    text = group.players.toString() + " " + cohortLabel + " · " +
                        group.playersWithSeasonData + " with results · " +
                        group.playersWithValidStats + " with valid game stats",
                    style = TextStyle(fontSize = 10.5.sp),
                    color = colors.textDim,
                )
            }
        }
        HairLine()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GroupMetric(label = "Hold", value = formatPct(group.holdPct), modifier = Modifier.weight(1f))
            GroupMetric(label = "Break", value = formatPct(group.breakPct), modifier = Modifier.weight(1f))
            GroupMetric(label = "H+B", value = group.combined?.let { formatNumber(it) } ?: "—", modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "W–L " + group.wins + "–" + group.losses + " · " + (group.winPct?.let { formatPct(it) } ?: "—") + " wins",
                style = TextStyle(fontSize = 11.sp),
                color = colors.textDim,
            )
            Text(
                text = group.matches.toString() + " matches",
                style = TextStyle(fontSize = 11.sp),
                color = colors.textFaint,
            )
        }
        Text(
            text = "Service " + group.serviceGamesWon + "/" + group.serviceGames + " games · Return " + group.returnGamesWon + "/" + group.returnGames + " games",
            style = TextStyle(fontSize = 10.sp),
            color = colors.textFaint,
        )
    }
}

@Composable
private fun GroupMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp))
            .background(colors.accentSoft.copy(alpha = 0.45f))
            .padding(horizontal = 9.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 10.sp), color = colors.textFaint)
        Text(value, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = colors.text)
    }
}

@Composable
private fun PlayerStatsLoading() {
    val colors = PaceTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Text("Reading annual player statistics…", color = colors.textDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayerStatsError(message: String) {
    val colors = PaceTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Player Stats unavailable", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = colors.textDim, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyPlayerStats(text: String) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(text, color = PaceTheme.colors.textDim, fontSize = 12.sp)
    }
}

private fun formatPct(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"

private fun formatNumber(value: Double): String =
    String.format(Locale.US, "%.1f", value)

private fun formatMetric(value: Double, metric: PlayerRankingMetric): String =
    if (metric == PlayerRankingMetric.COMBINED) formatNumber(value) else formatPct(value)
