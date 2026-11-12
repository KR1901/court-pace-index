import unittest
from collections import defaultdict
from datetime import date

from build_player_stats import (
    add_player_match,
    height_band_id,
    match_buckets,
    match_has_result,
    match_is_retirement,
    match_identity_key,
    normalize_name,
    normalize_country_code,
    _add_player_alias,
    parse_current_top100,
    parse_current_top1000,
    parse_kickserve_live_rankings,
    parse_tml_ranking_archive_snapshot,
    map_live_rankings_to_tml_ids,
    supplement_top100_from_tml,
    max_published_rank,
    select_ranking_snapshot_date,
    select_ranking_snapshot_dates,
    unresolved_top100_count,
    unresolved_ranking_count,
    service_return_contribution,
    supplement_missing_heights_from_sackmann,
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
            140: "under183",
            182: "under183",
            183: "183to185",
            185: "183to185",
            186: "186to188",
            188: "186to188",
            189: "189to191",
            191: "189to191",
            192: "192to194",
            194: "192to194",
            195: "195plus",
            220: "195plus",
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

    def test_sackmann_height_fallback_only_fills_unique_missing_tml_players(self):
        players = {
            "P1": {
                "id": "P1", "name": "Player One", "country": "USA",
                "heightCm": None, "heightSource": None,
            },
            "P2": {
                "id": "P2", "name": "Already Known", "country": "ESP",
                "heightCm": 190, "heightSource": "tml-player-database",
            },
            "P3": {
                "id": "P3", "name": "Ambiguous Player", "country": "FRA",
                "heightCm": None, "heightSource": None,
            },
        }
        aliases = {
            "playerone|USA": {"P1"},
            "alreadyknown|ESP": {"P2"},
            "ambiguousplayer|FRA": {"P3", "P4"},
        }
        rows = [
            {
                "name_first": "Player", "name_last": "One",
                "ioc": "USA", "height": "188", "player_id": "200001",
            },
            {
                "name_first": "Already", "name_last": "Known",
                "ioc": "ESP", "height": "195", "player_id": "200002",
            },
            {
                "name_first": "Ambiguous", "name_last": "Player",
                "ioc": "FRA", "height": "193", "player_id": "200003",
            },
            {
                "name_first": "Player", "name_last": "Without Height",
                "ioc": "GBR", "height": "", "player_id": "200004",
            },
        ]

        filled = supplement_missing_heights_from_sackmann(rows, players, aliases)

        self.assertEqual(filled, 1)
        self.assertEqual(players["P1"]["heightCm"], 188)
        self.assertEqual(
            players["P1"]["heightSource"],
            "jeff-sackmann-tennis-abstract-player-table",
        )
        self.assertEqual(players["P2"]["heightCm"], 190)
        self.assertEqual(players["P2"]["heightSource"], "tml-player-database")
        self.assertIsNone(players["P3"]["heightCm"])

    def test_sackmann_height_fallback_uses_safe_middle_name_aliases(self):
        players = {
            "P1": {
                "id": "P1", "name": "Taylor Fritz", "country": "USA",
                "heightCm": None, "heightSource": None,
            },
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Taylor Fritz", "USA", "P1")
        rows = [{
            "name_first": "Taylor",
            "name_last": "Harry Fritz",
            "ioc": "USA",
            "height": "193",
        }]

        self.assertEqual(
            supplement_missing_heights_from_sackmann(rows, players, aliases), 1,
        )
        self.assertEqual(players["P1"]["heightCm"], 193)

    def test_sackmann_height_fallback_never_crosses_country_when_names_collide(self):
        players = {
            "P1": {
                "id": "P1", "name": "Alex Smith",
                "country": "USA", "heightCm": None, "heightSource": None,
            },
        }
        aliases = {
            "alexsmith|USA": {"P1"},
            "alexsmith|GBR": set(),
            "alexsmith": {"P1"},
        }
        rows = [{
            "name_first": "Alex", "name_last": "Smith",
            "ioc": "GBR", "height": "188",
        }]
        self.assertEqual(
            supplement_missing_heights_from_sackmann(rows, players, aliases), 0,
        )
        self.assertIsNone(players["P1"]["heightCm"])

    def test_sackmann_height_fallback_accepts_documented_legacy_header_variant(self):
        players = {
            "P1": {
                "id": "P1", "name": "Legacy Player",
                "country": "USA", "heightCm": None, "heightSource": None,
            },
        }
        aliases = {"legacyplayer|USA": {"P1"}}
        rows = [{
            "first_name": "Legacy",
            "last_name": "Player",
            "country_code": "USA",
            "height": "192",
        }]
        self.assertEqual(
            supplement_missing_heights_from_sackmann(rows, players, aliases), 1,
        )
        self.assertEqual(players["P1"]["heightCm"], 192)

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

    def test_tml_top1000_parser_preserves_cutoff_ties_and_unresolved_ids(self):
        rows = [
            {"id": f"P{rank}", "rank": rank, "name": f"Player {rank}", "ioc": "USA"}
            for rank in range(1, 1001)
        ]
        rows.append({"id": "P1001", "rank": 1000, "name": "Co-ranked at cutoff"})
        rows.append({"id": "NA", "rank": 500, "name": "Unknown"})
        payload = {"rankings": rows}

        parsed = parse_current_top1000(payload, "2026 snapshot")
        self.assertEqual(len(parsed), 1001)
        self.assertEqual(parsed[0]["rank"], 1)
        self.assertEqual(parsed[-1]["rank"], 1000)
        self.assertEqual(unresolved_ranking_count(payload, 500), 1)
        self.assertEqual(unresolved_ranking_count(payload, 1000), 1)
        self.assertEqual(max_published_rank(payload), 1000)
        self.assertNotIn("NA", {row["id"] for row in parsed})

    def test_top1000_parser_fails_closed_on_duplicate_ids_and_short_top100(self):
        rows = [
            {"id": f"P{rank}", "rank": rank, "name": f"Player {rank}"}
            for rank in range(1, 101)
        ]
        rows[-1]["id"] = rows[0]["id"]
        with self.assertRaises(RuntimeError):
            parse_current_top1000({"rankings": rows})
        with self.assertRaises(RuntimeError):
            parse_current_top1000({"rankings": rows[:98]})

    def test_ranking_parser_records_source_depth_separately_from_requested_cutoff(self):
        rows = [
            {"id": f"P{rank}", "rank": rank, "name": f"Player {rank}"}
            for rank in range(1, 501)
        ]
        payload = {"rankings": rows}
        self.assertEqual(max_published_rank(payload), 500)
        self.assertEqual(len(parse_current_top1000(payload)), 500)

    def test_rank_cutoff_does_not_require_exactly_one_row_per_rank_position(self):
        # A source may have skipped rank numbers and/or ties. The cohort is every
        # published row whose official rank is <= 1000, not an invented 1000 rows.
        rows = [
            {"id": f"P{rank}", "rank": rank, "name": f"Player {rank}"}
            for rank in range(1, 1000)
            if rank % 10 != 0
        ]
        rows.append({"id": "P1100", "rank": 1100, "name": "Player 1100"})
        parsed = parse_current_top1000({"rankings": rows})
        self.assertEqual(len(parsed), 900)
        self.assertEqual(parsed[-1]["rank"], 999)
        self.assertEqual(max_published_rank({"rankings": rows}), 1100)

    def test_live_rankings_crosswalk_internal_ids_only_through_unique_normalized_names(self):
        snapshot = {
            "players": [
                {"id": "24008", "rank": 2, "name": "Alexander Zverev"},
                {"id": "missing-1", "rank": 450, "name": "Unknown Rank 450"},
                {"id": "missing-2", "rank": 900, "name": "Unknown Rank 900"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 3,
        }
        aliases = {
            "alexanderzverev": {"Z355"},
            "unknownrank450": {"P450", "P450B"},
        }

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(result["players"][0]["id"], "Z355")
        self.assertEqual(result["unresolvedTop100"], 0)
        self.assertEqual(result["unresolvedTop500"], 1)
        self.assertEqual(result["unresolvedPlayers"], 2)
        self.assertEqual(result["rankedEntries"], 3)

    def test_live_rankings_prefer_exact_name_before_initial_alias_collisions(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 118, "name": "Jesper De Jong", "country": "NLD"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Jesper De Jong", "NLD", "TML-JESPER")
        _add_player_alias(aliases, "J. De Jong", "NLD", "TML-INITIAL")

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual([row["id"] for row in result["players"]], ["TML-JESPER"])
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_live_rankings_resolve_middle_names_against_shorter_tml_names(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 10, "name": "Taylor Harry Fritz", "country": "USA"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Taylor Fritz", "USA", "TML-FRITZ")

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(result["players"][0]["id"], "TML-FRITZ")
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_live_rankings_resolve_compound_names_with_unique_adjacent_name_tokens(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 70, "name": "Jaume Antoni Munar Clar", "country": "ESP"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Jaume Munar", "ESP", "TML-MUNAR")

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(result["players"][0]["id"], "TML-MUNAR")
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_live_rankings_keep_multi_token_aliases_unresolved_when_country_candidates_conflict(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 70, "name": "Daniel Merida Aguilar", "country": "ESP"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Daniel Merida", "ESP", "TML-MERIDA")
        _add_player_alias(aliases, "Daniel Aguilar", "ESP", "TML-AGUILAR")

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(result["players"], [])
        self.assertEqual(result["unresolvedPlayers"], 1)
        self.assertEqual(result["unresolvedEntries"][0]["reason"], "ambiguous exact name/country match")

    def test_live_rankings_resolve_unique_first_initial_surname_alias_in_same_country(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 215, "name": "S. Tsitsipas", "country": "GRE"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Stefanos Tsitsipas", "GRE", "TML-TSITSIPAS")

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(len(result["players"]), 1)
        self.assertEqual(result["players"][0]["id"], "TML-TSITSIPAS")
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_legacy_atp_country_codes_normalize_before_cross_provider_matching(self):
        self.assertEqual(normalize_country_code("CHI"), "CHL")
        self.assertEqual(normalize_country_code("POR"), "PRT")
        self.assertEqual(normalize_country_code("SLO"), "SVN")
        self.assertEqual(normalize_country_code("GRE"), "GRC")
        self.assertEqual(normalize_country_code("INA"), "IDN")
        self.assertEqual(normalize_country_code("TPE"), "TWN")
        self.assertEqual(normalize_country_code("USA"), "USA")

    def test_live_rankings_resolve_same_player_across_legacy_ioc_codes(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 215, "name": "Player One", "country": "CHL"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = defaultdict(set)
        _add_player_alias(aliases, "Player One", "CHI", "TML-PLAYER-1")
        result = map_live_rankings_to_tml_ids(snapshot, aliases)
        self.assertEqual(result["players"][0]["id"], "TML-PLAYER-1")
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_live_rankings_do_not_fallback_to_same_name_in_another_country(self):
        snapshot = {
            "players": [
                {"id": "KICK-1", "rank": 215, "name": "Alex Smith", "country": "GBR"},
            ],
            "unresolvedTop100": 0,
            "unresolvedTop500": 0,
            "unresolvedPlayers": 0,
            "rankedEntries": 1,
            "unresolvedRanks": [],
        }
        aliases = {
            "alexsmith": {"TML-USA"},
            "alexsmith|USA": {"TML-USA"},
        }

        result = map_live_rankings_to_tml_ids(snapshot, aliases)

        self.assertEqual(result["players"], [])
        self.assertEqual(result["unresolvedPlayers"], 1)
        self.assertEqual(result["unresolvedEntries"][0]["reason"], "no exact name/country match")

    def test_live_full_rank_list_restores_missing_members_from_exact_tml_top100(self):
        expanded = {
            "players": [
                {"id": "P1", "rank": 1, "name": "Player One"},
                {"id": "P3", "rank": 3, "name": "Player Three"},
            ],
            "unresolvedPlayers": 2,
            "unresolvedTop100": 1,
            "unresolvedTop500": 1,
            "rankedEntries": 4,
            "maxRank": 1000,
        }
        top100 = {
            "players": [
                {"id": "P1", "rank": 1, "name": "Player One"},
                {"id": "P2", "rank": 2, "name": "Player Two"},
            ],
            "unresolvedPlayers": 0,
        }

        result = supplement_top100_from_tml(expanded, top100)

        self.assertEqual(
            [(row["id"], row["rank"]) for row in result["players"]],
            [("P1", 1), ("P2", 2), ("P3", 3)],
        )
        self.assertEqual(result["unresolvedTop100"], 0)
        self.assertEqual(result["rankedEntries"], 5)

    def test_live_full_rank_list_removes_restored_top100_rows_from_unresolved_audit(self):
        expanded = {
            "players": [
                {"id": "P1", "rank": 1, "name": "Player One"},
                {"id": "P3", "rank": 3, "name": "Player Three"},
            ],
            "unresolvedPlayers": 2,
            "unresolvedTop500": 2,
            "rankedEntries": 4,
            "maxRank": 1000,
            "unresolvedRanks": [2, 400],
            "unresolvedEntries": [
                {"rank": 2, "name": "Player Two", "country": "USA", "reason": "no match"},
                {"rank": 400, "name": "Unknown Four Hundred", "country": "GBR", "reason": "no match"},
            ],
        }
        top100 = {
            "players": [
                {"id": "P1", "rank": 1, "name": "Player One"},
                {"id": "P2", "rank": 2, "name": "Player Two"},
            ],
            "unresolvedPlayers": 0,
        }

        result = supplement_top100_from_tml(expanded, top100)

        self.assertEqual(result["unresolvedRanks"], [400])
        self.assertEqual(
            [row["rank"] for row in result["unresolvedEntries"]],
            [400],
        )

    def test_live_full_rank_list_rejects_top100_rank_conflicts(self):
        expanded = {
            "players": [{"id": "P1", "rank": 2, "name": "Player One"}],
            "unresolvedPlayers": 0,
        }
        top100 = {
            "players": [{"id": "P1", "rank": 1, "name": "Player One"}],
        }
        with self.assertRaises(RuntimeError):
            supplement_top100_from_tml(expanded, top100)

    def test_tml_archive_parser_selects_exact_year_end_date_and_keeps_cutoff_ties(self):
        payload = (
            "date,rank,name,id,points,ioc\n"
            "20251215,1,Player One,P001,1000,USA\n"
            "20251222,1,Player One,P001,1100,USA\n"
            "20251222,500,Player FiveHundred,P500,20,FRA\n"
            "20251222,1000,Player OneThousand,P1000,5,ESP\n"
            "20251222,1000,Co-ranked Player,P1001,5,ITA\n"
            "20251222,450,Unknown,,10,\n"
        ).encode("utf-8")

        result = parse_tml_ranking_archive_snapshot(payload, "2025-12-22")

        self.assertEqual(len(result["players"]), 4)
        self.assertEqual(result["maxRank"], 1000)
        self.assertEqual(result["unresolvedTop100"], 0)
        self.assertEqual(result["unresolvedTop500"], 1)
        self.assertEqual(result["unresolvedPlayers"], 1)
        self.assertEqual(result["rankedEntries"], 5)
        self.assertEqual(result["players"][-1]["rank"], 1000)

    def test_tml_archive_parser_fails_when_selected_ranking_date_is_missing(self):
        payload = (
            "date,rank,name,id,points,ioc\n"
            "20251215,1,Player One,P001,1000,USA\n"
        ).encode("utf-8")
        with self.assertRaises(RuntimeError):
            parse_tml_ranking_archive_snapshot(payload, "2025-12-22")

    def test_kickserve_parser_uses_official_atp_rank_and_ignores_wta(self):
        payload = (
            "player,id,tour,country,official_rank,points,elo\n"
            "Player One,P001,ATP,USA,1,1000,1800\n"
            "Player FiveHundred,P500,ATP,FRA,500,20,1500\n"
            "Player OneThousand,P1000,ATP,ESP,1000,5,1400\n"
            "WTA Player,W001,WTA,USA,1,1200,1900\n"
            "Unranked Player,PX,ATP,GBR,,0,1300\n"
        ).encode("utf-8")

        result = parse_kickserve_live_rankings(payload)

        self.assertEqual([row["id"] for row in result["players"]], ["P001", "P500", "P1000"])
        self.assertEqual(result["maxRank"], 1000)
        self.assertEqual(result["rankedEntries"], 3)
        self.assertEqual(result["unresolvedPlayers"], 0)

    def test_top100_placeholder_id_is_counted_but_not_treated_as_a_player(self):
        rows = [
            {"id": str(10000 + rank), "rank": rank, "name": f"Player {rank}", "ioc": "USA"}
            for rank in range(1, 101)
        ]
        rows.append({"id": "NA", "rank": 24, "name": "Unknown", "ioc": ""})
        payload = {"rankings": rows}

        parsed = parse_current_top100(payload, "1974 snapshot 1974-12-30")

        self.assertEqual(len(parsed), 100)
        self.assertEqual(unresolved_top100_count(payload), 1)
        self.assertNotIn("NA", {row["id"] for row in parsed})
        self.assertEqual({row["rank"] for row in parsed if row["rank"] == 24}, {24})

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
