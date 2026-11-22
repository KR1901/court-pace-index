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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun H2HHost() {
    H2HScreen()
}

@Composable
fun H2HScreen() {
    val colors = PaceTheme.colors
    val currentYear = remember { LocalDate.now().year }
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
                    matches = loaded.player1.matches,
                    year = currentYear,
                    surface = ytdSurface,
                )
                val secondRecord = H2HAnalytics.record(
                    playerId = loaded.player2.player.id,
                    matches = loaded.player2.matches,
                    year = currentYear,
                    surface = ytdSurface,
                )
                RecordSection(
                    title = "YEAR-TO-DATE · $currentYear",
                    surface = ytdSurface,
                    onSurface = { ytdSurface = it },
                    firstName = loaded.player1.player.name,
                    firstRecord = firstRecord,
                    secondName = loaded.player2.player.name,
                    secondRecord = secondRecord,
                )
            }

            item {
                val firstRecord = H2HAnalytics.record(
                    playerId = loaded.player1.player.id,
                    matches = loaded.player1.matches,
                    year = null,
                    surface = careerSurface,
                )
                val secondRecord = H2HAnalytics.record(
                    playerId = loaded.player2.player.id,
                    matches = loaded.player2.matches,
                    year = null,
                    surface = careerSurface,
                )
                RecordSection(
                    title = "CAREER",
                    surface = careerSurface,
                    onSurface = { careerSurface = it },
                    firstName = loaded.player1.player.name,
                    firstRecord = firstRecord,
                    secondName = loaded.player2.player.name,
                    secondRecord = secondRecord,
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
        Text(
            text = "${meetings.size} recorded meetings",
            style = TextStyle(fontSize = 11.sp),
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
        FactLine("Current rank", profile.currentRank?.let { "#$it" } ?: "Unranked")
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
    secondName: String,
    secondRecord: H2HRecord,
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
                )
                RecordPlayerColumn(
                    modifier = Modifier.weight(1f),
                    name = secondName,
                    record = secondRecord,
                )
            }
            HairLine(Modifier.padding(vertical = 9.dp))
            Text(
                text = "${firstRecord.matches} and ${secondRecord.matches} matches in this view",
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
) {
    val colors = PaceTheme.colors
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
            text = "${record.wins}–${record.losses}",
            style = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold),
            color = colors.accent,
        )
        Text(
            text = "${record.titles} titles",
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
            Text(
                text = H2HAnalytics.formatDate(match.date),
                style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                color = colors.textDim,
            )
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
            if (match.surface == "hard") {
                when (match.indoor) {
                    true -> "Indoor"
                    false -> "Outdoor"
                    null -> null
                }
            } else null,
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
