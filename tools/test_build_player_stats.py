import unittest
from datetime import date

from build_player_stats import (
    add_player_match,
    height_band_id,
    match_buckets,
    match_has_result,
    match_is_retirement,
    match_identity_key,
    normalize_name,
    parse_current_top100,
    select_ranking_snapshot_date,
    select_ranking_snapshot_dates,
    service_return_contribution,
)


class PlayerStatsFormulaTest(unittest.TestCase):
    def test_hold_and_break_components_use_game_denominators(self):
        row = {
            "w_SvGms": "10",
            "w_bpFaced": "4",
            "w_bpSaved": "3",
            "l_SvGms": "9",
            "l_bpFaced": "3",
            "l_bpSaved": "2",
        }
        # Winner: 9/10 holds and converts 1/9 return games.
        # Loser: 8/9 holds and converts 1/10 return games.
        self.assertEqual(service_return_contribution(row), (10, 9, 9, 8))

    def test_missing_or_inconsistent_stat_line_is_not_estimated(self):
        missing = {"w_SvGms": "10"}
        impossible = {
            "w_SvGms": "2",
            "w_bpFaced": "3",
            "w_bpSaved": "0",
            "l_SvGms": "8",
            "l_bpFaced": "1",
            "l_bpSaved": "0",
        }
        self.assertIsNone(service_return_contribution(missing))
        self.assertIsNone(service_return_contribution(impossible))

    def test_breakpoint_saves_cannot_exceed_faced(self):
        row = {
            "w_SvGms": "10",
            "w_bpFaced": "2",
            "w_bpSaved": "3",
            "l_SvGms": "9",
            "l_bpFaced": "3",
            "l_bpSaved": "1",
        }
        self.assertIsNone(service_return_contribution(row))

    def test_walkovers_and_no_play_defaults_are_excluded_but_retirements_count_as_results(self):
        self.assertFalse(match_has_result("W/O", "A", "B"))
        self.assertFalse(match_has_result("DEF", "A", "B"))
        self.assertFalse(match_has_result("ABN", "A", "B"))
        self.assertTrue(match_has_result("6-4 2-1 RET", "A", "B"))
        self.assertTrue(match_is_retirement("6-4 2-1 RET"))
        self.assertFalse(match_is_retirement("6-4 6-3"))

    def test_match_result_requires_two_distinct_player_ids(self):
        self.assertFalse(match_has_result("6-4 6-3", "A", "A"))
        self.assertFalse(match_has_result("6-4 6-3", "", "B"))

    def test_calendar_surface_buckets_keep_indoor_outdoor_distinct(self):
        self.assertEqual(match_buckets("Hard", "I"), ("all", "hardAll", "hardIndoor"))
        self.assertEqual(match_buckets("Hard", "O"), ("all", "hardAll", "hardOutdoor"))
        self.assertEqual(match_buckets("Hard", ""), ("all", "hardAll", "hardUnknown"))
        self.assertEqual(match_buckets("Clay", "O"), ("all", "clay"))
        self.assertEqual(match_buckets("Grass", "I"), ("all", "grass"))
        self.assertEqual(match_buckets("Carpet", "I"), ("all", "carpet"))

    def test_height_bands_are_exhaustive_at_cut_points(self):
        cases = {
            182: "under183",
            183: "183to190",
            190: "183to190",
            191: "191to194",
            194: "191to194",
            195: "195plus",
            211: "195plus",
        }
        for height, expected in cases.items():
            with self.subTest(height=height):
                self.assertEqual(height_band_id(height), expected)
        self.assertIsNone(height_band_id(None))
        self.assertIsNone(height_band_id(139))
        self.assertIsNone(height_band_id(221))

    def test_annual_aggregate_tracks_results_and_game_denominators(self):
        aggregate = {}
        add_player_match(
            aggregate, "P1", 2026, ("all", "hardAll", "hardIndoor"),
            True, 10, 9, 9, 1, True,
        )
        add_player_match(
            aggregate, "P2", 2026, ("all", "hardAll", "hardIndoor"),
            False, 9, 8, 10, 1, True,
        )
        stats = aggregate["P1"][2026]["hardIndoor"]
        self.assertEqual(stats["mp"], 1)
        self.assertEqual(stats["w"], 1)
        self.assertEqual(stats["sp"], 10)
        self.assertEqual(stats["sw"], 9)
        self.assertEqual(stats["rp"], 9)
        self.assertEqual(stats["rw"], 1)
        self.assertEqual(stats["sm"], 1)
        self.assertNotIn(2025, aggregate["P1"])

    def test_match_identity_is_stable_across_winner_loser_order(self):
        first = match_identity_key("2026-429", "38", "M0QI", "C0C8")
        reversed_players = match_identity_key("2026-429", "38", "C0C8", "M0QI")
        self.assertEqual(first, "2026-429|38|C0C8|M0QI")
        self.assertEqual(first, reversed_players)
        with self.assertRaises(ValueError):
            match_identity_key("2026-429", "", "M0QI", "C0C8")

    def test_name_matching_ignores_accents_and_punctuation(self):
        self.assertEqual(normalize_name("João Fonseca"), "joaofonseca")
        self.assertEqual(normalize_name("Botic van de Zandschulp"), "boticvandezandschulp")

    def test_tml_yes_no_indoor_flags_are_split_into_real_environment_buckets(self):
        for value in ("Yes", "YES", "Y", "true", "1", "Indoor", "I"):
            with self.subTest(value=value):
                self.assertEqual(match_buckets("Hard", value), ("all", "hardAll", "hardIndoor"))
        for value in ("No", "NO", "N", "false", "0", "Outdoor", "O"):
            with self.subTest(value=value):
                self.assertEqual(match_buckets("Hard", value), ("all", "hardAll", "hardOutdoor"))
        for value in ("", None, "unknown", "n/a"):
            with self.subTest(value=value):
                self.assertEqual(match_buckets("Hard", value), ("all", "hardAll", "hardUnknown"))

    def test_latest_ranking_snapshot_is_selected_by_date_not_response_order(self):
        selected = select_ranking_snapshot_date(
            {"dates": ["2026-11-02", "2026-11-09", "2026-11-16", "bad-date"]},
            today=__import__("datetime").date(2026, 11, 11),
        )
        self.assertEqual(selected, "2026-11-09")

    def test_year_end_ranking_dates_select_last_snapshot_within_each_calendar_year(self):
        selected = select_ranking_snapshot_dates(
            {
                "dates": [
                    "2015-12-21", "2015-12-28", "2016-01-04",
                    "2016-11-07", "2016-12-26", "2016-11-02",
                    "2026-11-09", "2026-11-16", "bad-date",
                ],
            },
            first_year=2015,
            last_year=2016,
            today=date(2016, 11, 10),
        )
        self.assertEqual(selected, {2015: "2015-12-28", 2016: "2016-11-07"})

    def test_year_end_ranking_dates_fail_closed_when_a_season_is_missing(self):
        with self.assertRaisesRegex(RuntimeError, "2016"):
            select_ranking_snapshot_dates(
                {"dates": ["2015-12-28"]},
                first_year=2015,
                last_year=2016,
                today=date(2016, 11, 10),
            )

    def test_tml_top100_parser_preserves_player_ids_without_name_matching(self):
        rows = [
            {"id": str(10000 + rank), "rank": rank, "name": f"Player {rank}", "ioc": "USA", "points": 1000-rank}
            for rank in range(1, 101)
        ]
        parsed = parse_current_top100({"rankings": rows})
        self.assertEqual(len(parsed), 100)
        self.assertEqual(parsed[0]["id"], "10001")
        self.assertEqual(parsed[0]["rank"], 1)
        self.assertEqual(parsed[-1]["id"], "10100")

    def test_tml_top100_parser_preserves_players_with_tied_historical_ranks(self):
        rows = [
            {"id": str(10000 + rank), "rank": rank, "name": f"Player {rank}", "ioc": "USA"}
            for rank in range(1, 101)
        ]
        rows.append({"id": "10101", "rank": 45, "name": "Co-ranked Player", "ioc": "FRA"})

        parsed = parse_current_top100({"rankings": rows}, "1973 snapshot 1973-12-31")

        self.assertEqual(len(parsed), 101)
        rank_45 = [row for row in parsed if row["rank"] == 45]
        self.assertEqual({row["id"] for row in rank_45}, {"10045", "10101"})
        self.assertEqual(parsed[-1]["rank"], 100)

    def test_tml_top100_parser_fails_closed_on_duplicate_or_missing_identity(self):
        rows = [
            {"id": str(10000 + rank), "rank": rank, "name": f"Player {rank}"}
            for rank in range(1, 101)
        ]
        rows[-1]["id"] = rows[0]["id"]
        with self.assertRaises(RuntimeError):
            parse_current_top100({"rankings": rows})
        with self.assertRaises(RuntimeError):
            parse_current_top100({"rankings": rows[:-1]})


if __name__ == "__main__":
    unittest.main()
