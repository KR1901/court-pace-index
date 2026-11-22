package ink.courtpace.index.core.h2h

import ink.courtpace.index.core.playerstats.PlayerSeasonStats
import ink.courtpace.index.core.playerstats.PlayerStatsBucket
import ink.courtpace.index.core.playerstats.PlayerStatsPlayer
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H2HAnalyticsTest {
    private fun match(id:String,year:Int,surface:String,winner:String="p1") = H2HMatch(
        id=id,eventId="event-$id",eventName="Test Open",
        date=LocalDate.of(year,3,1),year=year,surface=surface,teamEvent=false,
        round="R16",level="A",winnerId=winner,
        winnerName=if(winner=="p1") "Player One" else "Player Two",
        loserId=if(winner=="p1") "p2" else "p1",
        loserName=if(winner=="p1") "Player Two" else "Player One",score="6-4 6-4",
    )

    private fun statsPlayer() = PlayerStatsPlayer(
        id="p1",name="Player One",
        seasons=listOf(
            PlayerSeasonStats(y=2026,b=mapOf(
                "all" to PlayerStatsBucket(mp=5,w=3,l=2,titles=4),
                "hardAll" to PlayerStatsBucket(mp=3,w=2,l=1,titles=2),
                "hardIndoor" to PlayerStatsBucket(mp=1,w=1,l=0,titles=1),
                "hardOutdoor" to PlayerStatsBucket(mp=1,w=1,l=0,titles=1),
                "hardUnknown" to PlayerStatsBucket(mp=1,w=0,l=1),
                "grass" to PlayerStatsBucket(mp=1,w=1,l=0,titles=1),
                "clay" to PlayerStatsBucket(mp=1,w=0,l=1,titles=1),
            )),
            PlayerSeasonStats(y=2025,b=mapOf(
                "all" to PlayerStatsBucket(mp=4,w=2,l=2,titles=3),
                "hardAll" to PlayerStatsBucket(mp=2,w=1,l=1,titles=1),
                "hardIndoor" to PlayerStatsBucket(mp=1,w=1,l=0,titles=1),
                "hardOutdoor" to PlayerStatsBucket(mp=1,w=0,l=1),
                "grass" to PlayerStatsBucket(mp=1,w=1,l=0,titles=1),
                "clay" to PlayerStatsBucket(mp=1,w=0,l=1,titles=1),
            )),
        ),
    )

    @Test
    fun ytd_and_career_records_use_the_selected_period_and_surface_bucket() {
        val player=statsPlayer()
        val ytd=H2HAnalytics.record(player,2026,H2HSurfaceFilter.ALL)
        val career=H2HAnalytics.record(player,null,H2HSurfaceFilter.ALL)
        val careerClay=H2HAnalytics.record(player,null,H2HSurfaceFilter.CLAY)
        assertEquals(3,ytd.wins); assertEquals(2,ytd.losses); assertEquals(5,ytd.matches)
        assertEquals(4,ytd.titles); assertEquals(60.0,ytd.winPercentage!!,0.001)
        assertEquals(5,career.wins); assertEquals(4,career.losses); assertEquals(9,career.matches)
        assertEquals(7,career.titles); assertEquals(55.555,career.winPercentage!!,0.01)
        assertEquals(0,careerClay.wins); assertEquals(2,careerClay.losses)
        assertEquals(2,careerClay.titles); assertEquals(0.0,careerClay.winPercentage!!,0.001)
    }

    @Test
    fun every_surface_filter_returns_its_own_ytd_and_career_title_count() {
        val player=statsPlayer()
        val expected=listOf(
            Triple(H2HSurfaceFilter.ALL,4,7), Triple(H2HSurfaceFilter.HARD,2,3),
            Triple(H2HSurfaceFilter.OUTDOOR_HARD,1,1), Triple(H2HSurfaceFilter.INDOOR_HARD,1,2),
            Triple(H2HSurfaceFilter.GRASS,1,2), Triple(H2HSurfaceFilter.CLAY,1,2),
        )
        expected.forEach { (surface,ytdTitles,careerTitles) ->
            assertEquals(ytdTitles,H2HAnalytics.record(player,2026,surface).titles)
            assertEquals(careerTitles,H2HAnalytics.record(player,null,surface).titles)
        }
    }

    @Test
    fun hard_combines_indoor_outdoor_and_unknown_hard_match_results() {
        val player=statsPlayer()
        val hard=H2HAnalytics.record(player,2026,H2HSurfaceFilter.HARD)
        val indoor=H2HAnalytics.record(player,2026,H2HSurfaceFilter.INDOOR_HARD)
        val outdoor=H2HAnalytics.record(player,2026,H2HSurfaceFilter.OUTDOOR_HARD)
        assertEquals(2,hard.wins); assertEquals(1,hard.losses); assertEquals(3,hard.matches)
        assertEquals(2,hard.titles); assertEquals(1,indoor.wins); assertEquals(1,indoor.titles)
        assertEquals(1,outdoor.wins); assertEquals(1,outdoor.titles)
        assertEquals(100.0,indoor.winPercentage!!,0.001)
    }

    @Test
    fun head_to_head_win_count_follows_winner_id() {
        val meetings=listOf(match("a",2026,"hard"),match("b",2025,"clay","p2"),match("c",2024,"grass"))
        assertEquals(2,H2HAnalytics.winsAgainst("p1",meetings))
        assertEquals(1,H2HAnalytics.winsAgainst("p2",meetings))
    }

    @Test
    fun missing_player_aggregate_is_not_fabricated_as_zero() {
        val record=H2HAnalytics.record(null,null,H2HSurfaceFilter.ALL)
        assertNull(record.wins); assertNull(record.losses); assertNull(record.titles)
        assertNull(record.matches); assertNull(record.winPercentage)
    }

    @Test
    fun height_is_shown_in_metric_and_imperial_units() {
        assertEquals("198 cm · 6′6″",H2HAnalytics.formatHeight(198))
        assertEquals("Unavailable",H2HAnalytics.formatHeight(null))
        assertEquals("Unavailable",H2HAnalytics.formatHeight(250))
    }

    @Test
    fun meeting_dates_use_dd_mm_yyyy() {
        assertEquals("07/02/2026",H2HAnalytics.formatDate(LocalDate.of(2026,2,7)))
        assertEquals("Date unavailable",H2HAnalytics.formatDate(null))
    }

    @Test
    fun invalid_future_birth_date_is_not_converted_into_a_negative_age() {
        assertEquals(null,H2HAnalytics.age(LocalDate.of(2027,1,1),LocalDate.of(2026,11,22)))
        assertEquals(30,H2HAnalytics.age(LocalDate.of(1996,11,22),LocalDate.of(2026,11,22)))
    }
}
