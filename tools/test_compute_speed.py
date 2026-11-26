import hashlib
import json
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compute_speed
from davis_cup_policy import is_supported_davis_cup_calculation_year


def profile(
    player_id,
    name,
    *,
    serve_rate=None,
    concede_rate=0.08,
    concede_rate_source="pooled-fallback",
    concede_matches=0,
    concede_service_points=0.0,
    clay_aces=0.0,
    clay_points=0.0,
    clay_matches=0,
    hard_grass_aces=0.0,
    hard_grass_points=0.0,
    hard_grass_matches=0,
    serve_matches=0,
    clay_return_aces=0.0,
    clay_return_points=0.0,
    clay_return_matches=0,
    hard_grass_return_aces=0.0,
    hard_grass_return_points=0.0,
    hard_grass_return_matches=0,
    pooled_serve_aces=None,
    pooled_serve_points=None,
    pooled_serve_matches=None,
    pooled_return_aces=None,
    pooled_return_points=None,
    pooled_return_matches=None,
):
    return {
        "id": player_id,
        "name": name,
        "serve_rate": serve_rate,
        "concede_rate": concede_rate,
        "concede_rate_source": concede_rate_source,
        "concede_matches": concede_matches,
        "concede_service_points": concede_service_points,
        "serve_matches": serve_matches,
        "clay_matches": clay_matches,
        "hard_grass_matches": hard_grass_matches,
        "return_points": 1000.0,
        "clay_aces": clay_aces,
        "clay_service_points": clay_points,
        "hard_grass_aces": hard_grass_aces,
        "hard_grass_service_points": hard_grass_points,
        "pooled_serve_aces": (
            clay_aces + hard_grass_aces if pooled_serve_aces is None else pooled_serve_aces
        ),
        "pooled_serve_points": (
            clay_points + hard_grass_points if pooled_serve_points is None else pooled_serve_points
        ),
        "pooled_serve_matches": (
            clay_matches + hard_grass_matches if pooled_serve_matches is None else pooled_serve_matches
        ),
        "pooled_return_aces": (
            (concede_rate or 0.0) * concede_service_points
            if pooled_return_aces is None else pooled_return_aces
        ),
        "pooled_return_points": (
            concede_service_points
            if pooled_return_points is None else pooled_return_points
        ),
        "pooled_return_matches": (
            concede_matches
            if pooled_return_matches is None else pooled_return_matches
        ),
        "clay_return_aces": clay_return_aces,
        "clay_return_points": clay_return_points,
        "clay_return_matches": clay_return_matches,
        "hard_grass_return_aces": hard_grass_return_aces,
        "hard_grass_return_points": hard_grass_return_points,
        "hard_grass_return_matches": hard_grass_return_matches,
    }


class HistoricalCalculationDiagnosticsTests(unittest.TestCase):
    def test_davis_cup_tie_labels_are_grouped_into_one_target_event(self):
        self.assertEqual("daviscup", compute_speed.tournament_id("Davis Cup Finals RR: FRA vs JPN"))
        self.assertEqual("daviscup", compute_speed.tournament_id("Davis Cup WG1 PO: CHN vs POR"))

    def test_davis_cup_rows_are_valid_targets_but_never_profile_training(self):
        davis = {
            "tourney_level": "D", "tourney_name": "Davis Cup Finals F: ITA vs ESP",
            "winner_id": "1", "loser_id": "2",
            "w_svpt": "70", "l_svpt": "65", "w_ace": "8", "l_ace": "4",
        }
        atp = {
            "tourney_level": "250", "tourney_name": "Dallas",
            "winner_id": "3", "loser_id": "4",
            "w_svpt": "70", "l_svpt": "65", "w_ace": "8", "l_ace": "4",
        }
        valid = compute_speed.valid_rows([davis, atp])
        self.assertEqual(2, len(valid))
        self.assertEqual([atp], compute_speed.calculation_eligible_rows(valid))
        self.assertIn("daviscup", compute_speed.CALC_TARGET_ONLY_EVENT_IDS)

    def test_davis_cup_diagnostic_ids_do_not_collide_when_tie_match_numbers_repeat(self):
        rows = [
            {
                "tourney_level": "D", "tourney_name": "Davis Cup WG1: FRA vs JPN",
                "match_num": "1", "winner_id": "1", "loser_id": "2",
                "winner_name": "Server", "loser_name": "Returner",
                "w_svpt": "70", "l_svpt": "70", "w_ace": "15", "l_ace": "1",
            },
            {
                "tourney_level": "D", "tourney_name": "Davis Cup Finals: FRA vs JPN",
                "match_num": "1", "winner_id": "1", "loser_id": "2",
                "winner_name": "Server", "loser_name": "Returner",
                "w_svpt": "70", "l_svpt": "70", "w_ace": "1", "l_ace": "15",
            },
        ]
        diagnostics = compute_speed.event_side_diagnostics(rows, profiles={}, tour_rate=0.05)
        self.assertEqual({"dc001", "dc002"}, {
            side["matchId"] for side in diagnostics["sideDiagnostics"]
        })
        self.assertEqual(4, len(diagnostics["sideDiagnostics"]))

    def test_calculation_archive_starts_with_the_first_supported_season(self):
        self.assertEqual(1991, compute_speed.YEARS.start)

    def test_event_side_diagnostics_retain_high_and_low_matchups_and_full_denominators(self):
        rows = [{
            "winner_id": "p1", "winner_name": "Server One",
            "loser_id": "p2", "loser_name": "Returner Two",
            "w_svpt": "100", "l_svpt": "90", "w_ace": "8", "l_ace": "3",
            "match_num": "17",
        }]
        profiles = {
            "p1": {
                "serve_rate": 0.10, "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate": 0.08, "concede_rate_source": "career-history-surface-shrunk",
            },
            "p2": {
                "serve_rate": 0.09, "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate": 0.08, "concede_rate_source": "career-history-surface-shrunk",
            },
        }
        result = compute_speed.event_side_diagnostics(rows, profiles, 0.08)
        self.assertEqual(2, result["totalServerSides"])
        self.assertEqual(11, result["aces"])
        self.assertEqual(190, result["totalServicePoints"])
        self.assertEqual(2, len(result["sideDiagnostics"]))
        self.assertEqual(2, result["serverProfilesMatched"])
        self.assertEqual(2, result["returnerProfilesMatched"])
        self.assertEqual(0, result["serverProfilesFallback"])
        self.assertEqual(0, result["returnerProfilesFallback"])
        self.assertTrue(all(item["matchId"] == "m017" for item in result["sideDiagnostics"]))
        self.assertEqual(
            {"Server One", "Returner Two"},
            {item["server"] for item in result["sideDiagnostics"]} |
            {item["returner"] for item in result["sideDiagnostics"]},
        )
        for item in result["sideDiagnostics"]:
            expected = compute_speed.expected_calibrated_aces(
                item["serveRate"], item["returnerConcedeRate"], 0.08, item["servicePoints"]
            )
            self.assertAlmostEqual(max(expected, compute_speed.EXPECTED_ACES_FLOOR), item["expectedAces"])
            self.assertAlmostEqual(item["actualAces"] / item["expectedAces"], item["actualExpectedRatio"])


class BuildLiveProfilesTests(unittest.TestCase):
    def test_same_season_conflicting_player_ids_are_excluded_as_ambiguous(self):
        first = profile("id-a", "Alex Molcan", serve_rate=0.08, concede_rate=0.06,
                        concede_rate_source="surface-qualified")
        second = profile("id-b", "Alex Molčan", serve_rate=0.12, concede_rate=0.09,
                         concede_rate_source="surface-qualified")
        for item in (first, second):
            item.update({
                "serve_rate_season": 2026,
                "concede_rate_season": 2026,
                "serve_rate_source": "current-season-qualified",
                "serve_rate_sample_matches": 8,
                "serve_rate_sample_points": 500,
                "concede_service_points": 500,
            })

        deduped, diagnostics = compute_speed.dedupe_live_profiles_with_diagnostics([first, second])

        self.assertEqual([], deduped)
        self.assertEqual(1, diagnostics["ambiguousNameCollisionsExcluded"])
        self.assertEqual(1, diagnostics["duplicateNameRecordsCollapsed"])

    def test_older_duplicate_id_does_not_override_newer_current_profile(self):
        older = profile("old-id", "Martin Landaluce", serve_rate=0.0733, concede_rate=0.0232)
        older.update({
            "serve_rate_season": 2022,
            "serve_rate_source": "prior-season-shrunk",
            "serve_rate_sample_matches": 1,
            "serve_rate_sample_points": 63,
            "concede_rate_season": 2022,
            "concede_rate_source": "pooled-fallback",
        })
        current = profile("current-id", "Martin Landaluce", serve_rate=0.0609, concede_rate=0.0699)
        current.update({
            "serve_rate_season": 2026,
            "serve_rate_source": "current-season-qualified",
            "serve_rate_sample_matches": 11,
            "serve_rate_sample_points": 812,
            "concede_rate_season": 2026,
            "concede_rate_source": "surface-qualified",
            "concede_service_points": 812,
        })

        deduped = compute_speed.dedupe_live_profiles([older, current])

        self.assertEqual(1, len(deduped))
        self.assertEqual("current-season-qualified", deduped[0]["serve_rate_source"])
        self.assertEqual(0.0609, deduped[0]["serve_rate"])
        self.assertEqual(0.0699, deduped[0]["concede_rate"])

    def test_tml_cache_requires_recent_successful_fetch_manifest(self):
        now = datetime(2026, 11, 8, 12, 0, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            csv_path = Path(directory) / "tml.csv"
            metadata_path = Path(str(csv_path) + ".meta.json")
            payload = b"tourney_id,w_svpt\\n123,75\\n"
            csv_path.write_bytes(payload)
            manifest = {
                "fetchedAtUtc": (now - timedelta(days=3)).isoformat(),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }

            self.assertFalse(compute_speed.tml_cache_is_fresh(metadata_path, now=now))

            metadata_path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertTrue(compute_speed.tml_cache_is_fresh(metadata_path, now=now))

            expired = dict(manifest)
            expired["fetchedAtUtc"] = (now - timedelta(days=8)).isoformat()
            metadata_path.write_text(json.dumps(expired), encoding="utf-8")
            self.assertFalse(compute_speed.tml_cache_is_fresh(metadata_path, now=now))

            metadata_path.write_text(json.dumps(manifest), encoding="utf-8")
            csv_path.write_bytes(payload + b"tampered")
            self.assertFalse(compute_speed.tml_cache_is_fresh(metadata_path, now=now))


    def test_preserves_qualified_current_profiles_and_backfills_prior_season_profiles(self):
        current = {
            "p1": profile(
                "p1", "Current Qualified", serve_rate=0.11,
                clay_aces=10, clay_points=100, clay_matches=4, serve_matches=4,
            ),
            "p2": profile(
                "p2", "Prior Season", serve_rate=None,
                clay_aces=6, clay_points=100, clay_matches=2,
            ),
            "p3": profile(
                "p3", "Low Sample", serve_rate=None,
                hard_grass_aces=5, hard_grass_points=70, hard_grass_matches=1,
            ),
            "p4": profile("p4", "No Current Serve Data", serve_rate=None),
        }
        prior = {
            "p2": profile(
                "p2", "Prior Season", serve_rate=0.095,
                clay_aces=20, clay_points=200, clay_matches=5, serve_matches=5,
            ),
            "p5": profile(
                "p5", "Historical Only", serve_rate=0.10,
                clay_aces=12, clay_points=120, clay_matches=4, serve_matches=4,
            ),
            "p6": profile(
                "p6", "Historical Low Sample", serve_rate=None,
                hard_grass_aces=2, hard_grass_points=45, hard_grass_matches=1,
            ),
        }

        def fake_load(year):
            return [{"year": year}]

        def fake_player_rates(rows):
            return prior if rows[0]["year"] == 2025 else {}

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertEqual(0.11, live["p1"]["serve_rate"])
        self.assertEqual("current-season-qualified", live["p1"]["serve_rate_source"])
        self.assertEqual(2026, live["p1"]["serve_rate_season"])

        self.assertIsNotNone(live["p2"]["serve_rate"])
        self.assertEqual("career-history-surface-shrunk", live["p2"]["serve_rate_source"])
        self.assertGreaterEqual(live["p2"]["serve_rate_sample_matches"], 7)
        self.assertEqual(2026, live["p2"]["serve_rate_season"])

        self.assertIsNotNone(live["p3"]["serve_rate"])
        self.assertGreaterEqual(live["p3"]["serve_rate"], 0.0)
        self.assertLessEqual(live["p3"]["serve_rate"], 1.0)
        self.assertEqual("career-history-pooled-shrunk", live["p3"]["serve_rate_source"])
        self.assertEqual(2026, live["p3"]["serve_rate_season"])
        self.assertEqual(150.0, live["p3"]["serve_rate_prior_points"])

        self.assertIsNotNone(live["p5"]["serve_rate"])
        self.assertEqual("career-history-surface-shrunk", live["p5"]["serve_rate_source"])
        self.assertEqual(2025, live["p5"]["serve_rate_season"])

        self.assertIsNotNone(live["p6"]["serve_rate"])
        self.assertEqual("career-history-pooled-shrunk", live["p6"]["serve_rate_source"])
        self.assertEqual(2025, live["p6"]["serve_rate_season"])

        # Never manufacture a rate if the archive contains no serve-side sample at all.
        self.assertIsNone(live["p4"]["serve_rate"])
        self.assertIsNone(live["p4"]["serve_rate_source"])

    def test_backfills_all_prior_archive_seasons_since_1991(self):
        current = {
            "p1": profile("p1", "Current", serve_rate=None, clay_aces=1, clay_points=20, clay_matches=1),
        }
        visited = []

        def fake_load(year):
            visited.append(year)
            return [{"year": year}]

        def fake_player_rates(rows):
            return {}

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            compute_speed.build_live_profiles(2026, current)

        self.assertEqual(list(range(2025, 1990, -1)), visited)


    def test_crosswalks_current_alphanumeric_atp_id_to_historical_numeric_id_by_unique_name(self):
        current = {
            "TFRI": profile("TFRI", "Taylor Fritz", serve_rate=None, concede_rate=None),
        }
        prior = {
            "126203": profile(
                "126203", "Taylor Fritz", serve_rate=None, concede_rate=None,
                clay_aces=10, clay_points=100, clay_matches=4, serve_matches=4,
                pooled_serve_aces=10, pooled_serve_points=100, pooled_serve_matches=4,
                pooled_return_aces=8, pooled_return_points=100, pooled_return_matches=4,
            ),
        }

        def fake_load(year):
            return [{"year": year}]

        def fake_player_rates(rows):
            return prior if rows[0]["year"] == 2025 else {}

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertIn("TFRI", live)
        self.assertNotIn("126203", live)
        self.assertIsNotNone(live["TFRI"]["serve_rate"])
        self.assertEqual("career-history-surface-shrunk", live["TFRI"]["serve_rate_source"])
        self.assertEqual("career-history-pooled-shrunk", live["TFRI"]["concede_rate_source"])

    def test_ambiguous_historical_full_name_is_not_crosswalked(self):
        current = {
            "TFRI": profile("TFRI", "Taylor Fritz", serve_rate=None, concede_rate=None),
        }
        duplicate = {
            "126203": profile(
                "126203", "Taylor Fritz", clay_aces=10, clay_points=100, clay_matches=4,
                serve_matches=4, pooled_serve_aces=10, pooled_serve_points=100,
                pooled_serve_matches=4,
            ),
            "999999": profile(
                "999999", "Taylor Fritz", clay_aces=15, clay_points=100, clay_matches=4,
                serve_matches=4, pooled_serve_aces=15, pooled_serve_points=100,
                pooled_serve_matches=4,
            ),
        }

        def fake_load(year):
            return [{"year": year}]

        def fake_player_rates(rows):
            return duplicate if rows[0]["year"] == 2025 else {}

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertIsNone(live["TFRI"]["serve_rate"])

    def test_unseen_event_players_receive_transparent_tour_average_prior(self):
        rows = [{
            "winner_id": "TFRI",
            "winner_name": "Taylor Fritz",
            "loser_id": "NEW1",
            "loser_name": "Unknown Debutant",
            "w_svpt": 80,
            "l_svpt": 82,
        }]
        profiles = {
            "126203": {
                "id": "126203", "name": "Taylor Fritz",
                "serve_rate": 0.13, "concede_rate": 0.08,
                "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate_source": "career-history-pooled-shrunk",
            },
        }
        coverage = compute_speed.ensure_event_profiles(rows, profiles, 0.08)

        self.assertEqual(0.13, profiles["TFRI"]["serve_rate"])
        self.assertEqual("career-history-surface-shrunk", profiles["TFRI"]["serve_rate_source"])
        self.assertEqual(0.08, profiles["NEW1"]["concede_rate"])
        self.assertEqual("tour-average-prior-only", profiles["NEW1"]["concede_rate_source"])
        self.assertEqual(1, coverage["tour_prior_returner_profiles"])
        self.assertEqual(2, coverage["tour_prior_sides"])

    def test_counts_every_side_that_uses_a_reused_neutral_profile(self):
        rows = [
            {
                "winner_id": "known", "winner_name": "Known Player",
                "loser_id": "debut", "loser_name": "Debut Player",
                "w_svpt": 80, "l_svpt": 82, "w_ace": 8, "l_ace": 7,
            },
            {
                "winner_id": "known", "winner_name": "Known Player",
                "loser_id": "debut", "loser_name": "Debut Player",
                "w_svpt": 81, "l_svpt": 83, "w_ace": 8, "l_ace": 7,
            },
        ]
        profiles = {
            "known": {
                "id": "known", "name": "Known Player",
                "serve_rate": 0.10, "concede_rate": 0.08,
                "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate_source": "career-history-pooled-shrunk",
            },
        }

        coverage = compute_speed.ensure_event_profiles(rows, profiles, 0.08)

        # The debut player is the server once and the returner once per match.
        # Count all four side uses, but retain one fallback assignment per role.
        self.assertEqual(4, coverage["tour_prior_sides"])
        self.assertEqual(2, coverage["tour_prior_server_side_uses"])
        self.assertEqual(2, coverage["tour_prior_returner_side_uses"])
        self.assertEqual(1, coverage["tour_prior_server_profiles"])
        self.assertEqual(1, coverage["tour_prior_returner_profiles"])
        self.assertEqual(1, coverage["missing_server_profile_before_fallback"])
        self.assertEqual(1, coverage["missing_returner_profile_before_fallback"])
        self.assertEqual(2, len(coverage["tour_prior_fallback_details"]))

    def test_tour_average_rate_is_computed_from_all_usable_service_points(self):
        rows = [
            {"w_ace": 6, "l_ace": 6, "w_svpt": 80, "l_svpt": 80},
            {"w_ace": 3, "l_ace": 3, "w_svpt": 40, "l_svpt": 40},
        ]
        self.assertAlmostEqual(18 / 240, compute_speed.tour_average_ace_rate(rows), places=12)

class ExpectedAceCalibrationTests(unittest.TestCase):
    def test_average_server_and_returner_expect_tour_average_ace_rate(self):
        expected = compute_speed.expected_calibrated_aces(
            serve_rate=0.08,
            concede_rate=0.08,
            tour_rate=0.08,
            service_points=75,
        )
        self.assertAlmostEqual(6.0, expected, places=12)

    def test_calibration_accounts_for_server_returner_and_tour_tendencies(self):
        average = compute_speed.expected_calibrated_aces(0.08, 0.08, 0.08, 75)
        stronger_server = compute_speed.expected_calibrated_aces(0.15, 0.08, 0.08, 75)
        stronger_returner = compute_speed.expected_calibrated_aces(0.08, 0.15, 0.08, 75)
        faster_tour = compute_speed.expected_calibrated_aces(0.08, 0.08, 0.12, 75)

        self.assertGreater(stronger_server, average)
        self.assertGreater(stronger_returner, average)
        self.assertLess(faster_tour, average)

    def test_invalid_endpoint_rates_are_not_calibrated(self):
        self.assertEqual(0.0, compute_speed.expected_calibrated_aces(0.0, 0.08, 0.08, 75))
        self.assertEqual(0.0, compute_speed.expected_calibrated_aces(0.08, 0.08, 1.0, 75))


class EventRawScoresCoverageTests(unittest.TestCase):
    def test_united_cup_is_mapped_and_eligible_archive_matches_are_rated(self):
        rows = [
            {
                "tourney_name": "United Cup",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": 80,
                "l_svpt": 82,
                "w_ace": 6,
                "l_ace": 6,
                "surface": "Hard",
            }
            for _ in range(10)
        ]
        profiles = {
            "p1": {"serve_rate": 0.08, "concede_rate": 0.08},
            "p2": {"serve_rate": 0.08, "concede_rate": 0.08},
        }

        raw, _, _, matches = compute_speed.event_raw_scores(
            rows, profiles, tour_rate=0.08
        )

        self.assertIn("unitedcup", raw)
        self.assertEqual(10, matches["unitedcup"])

    def test_includes_valid_positive_expected_aces_below_the_old_half_ace_floor(self):
        # Ten matches produce twenty server sides. Against an 8% tour baseline, each
        # side expects far fewer than half an ace, but the expected value is positive.
        rows = [
            {
                "tourney_name": "Paris Masters",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": 80,
                "l_svpt": 82,
                "w_ace": 1,
                "l_ace": 1,
                "surface": "Hard",
            }
            for _ in range(10)
        ]
        profiles = {
            "p1": {"serve_rate": 0.01, "concede_rate": 0.01},
            "p2": {"serve_rate": 0.01, "concede_rate": 0.01},
        }

        raw, _, _, matches = compute_speed.event_raw_scores(rows, profiles, tour_rate=0.08)

        expected = compute_speed.expected_calibrated_aces(0.01, 0.01, 0.08, 80)
        self.assertGreater(expected, 0.0)
        self.assertLess(expected, 0.5)
        self.assertIn("paris", raw)
        self.assertEqual(10, matches["paris"])
        self.assertAlmostEqual(1.0 / compute_speed.EXPECTED_ACES_FLOOR, raw["paris"], places=9)


    def test_event_raw_scores_defaults_to_the_actual_season_tour_rate(self):
        rows = [
            {
                "tourney_name": "Paris Masters",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": 80,
                "l_svpt": 80,
                "w_ace": 6,
                "l_ace": 6,
                "surface": "Hard",
            }
            for _ in range(10)
        ]
        profiles = {
            "p1": {"serve_rate": 0.075, "concede_rate": 0.075},
            "p2": {"serve_rate": 0.075, "concede_rate": 0.075},
        }

        raw, _, _, _ = compute_speed.event_raw_scores(rows, profiles)

        # 12 aces / 160 points = 7.5%; an average pair therefore expects 6 aces
        # from 80 service points, making the actual/expected event mean exactly 1.00.
        self.assertAlmostEqual(1.0, raw["paris"], places=12)



class PlayerRateEligibilityTests(unittest.TestCase):
    @staticmethod
    def rows(surface_counts):
        rows = []
        index = 0
        for surface, match_count, aces_per_match in surface_counts:
            for _ in range(match_count):
                index += 1
                rows.append({
                    "tourney_name": "Sample Event",
                    "surface": surface,
                    "winner_id": "player",
                    "winner_name": "Sample Player",
                    "loser_id": f"opponent-{index}",
                    "loser_name": f"Opponent {index}",
                    "w_svpt": 100,
                    "l_svpt": 100,
                    "w_ace": aces_per_match,
                    "l_ace": 8,
                })
        return rows

    def test_qualified_clay_profile_survives_one_or_two_hard_grass_matches(self):
        rows = self.rows([("Clay", 3, 10), ("Hard", 2, 30)])
        player = compute_speed.player_rates(rows)["player"]

        expected = (30.0 + 150.0 * 0.09) / 450.0
        self.assertAlmostEqual(expected, player["serve_rate"])
        self.assertEqual(150.0, player["serve_rate_prior_points"])
        self.assertEqual(3, player["serve_matches"])
        self.assertEqual(300.0, player["serve_service_points"])
        self.assertEqual(3, player["clay_matches"])
        self.assertEqual(2, player["hard_grass_matches"])

    def test_qualified_hard_grass_profile_survives_one_or_two_clay_matches(self):
        rows = self.rows([("Clay", 2, 30), ("Hard", 3, 20)])
        player = compute_speed.player_rates(rows)["player"]

        expected = (60.0 + 150.0 * 0.14) / 450.0
        self.assertAlmostEqual(expected, player["serve_rate"])
        self.assertEqual(150.0, player["serve_rate_prior_points"])
        self.assertEqual(3, player["serve_matches"])
        self.assertEqual(300.0, player["serve_service_points"])

    def test_both_qualified_surfaces_keep_the_historical_weighted_blend(self):
        rows = self.rows([("Clay", 3, 10), ("Hard", 3, 20)])
        player = compute_speed.player_rates(rows)["player"]

        clay = (30.0 + 150.0 * 0.09) / 450.0
        hard = (60.0 + 150.0 * 0.14) / 450.0
        self.assertAlmostEqual((1 / 3) * clay + (2 / 3) * hard, player["serve_rate"])
        self.assertEqual(150.0, player["serve_rate_prior_points"])
        self.assertEqual(6, player["serve_matches"])
        self.assertEqual(600.0, player["serve_service_points"])



class SpecialFormatEventEligibilityTests(unittest.TestCase):
    def test_next_gen_format_is_excluded_from_calc_population(self):
        rows = [
            {
                "tourney_name": event,
                "tourney_level": "F",
                "surface": "Hard",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": "60",
                "l_svpt": "65",
                "w_ace": "8",
                "l_ace": "7",
            }
            for event in (
                "Next Gen Finals",
                "Next Gen ATP Finals",
                "Next Generation ATP Finals",
            )
        ]
        self.assertEqual(3, len(compute_speed.valid_rows(rows)))
        self.assertEqual([], compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows)))

    def test_standard_atp_events_remain_in_calc_population(self):
        rows = [{
            "tourney_name": "Marseille",
            "tourney_level": "A",
            "surface": "Hard",
            "winner_id": "p1",
            "loser_id": "p2",
            "w_svpt": "60",
            "l_svpt": "65",
            "w_ace": "8",
            "l_ace": "7",
        }]
        eligible = compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows))
        self.assertEqual(["Marseille"], [row["tourney_name"] for row in eligible])


class CurrentSeasonSourceTests(unittest.TestCase):
    def test_tml_atp_250_and_500_levels_are_eligible(self):
        row = {
            "tourney_level": "250",
            "w_svpt": "80",
            "l_svpt": "82",
            "w_ace": "6",
            "l_ace": "6",
            "winner_id": "p1",
            "loser_id": "p2",
        }
        row_500 = dict(row, tourney_level="500")

        self.assertEqual(2, len(compute_speed.valid_rows([row, row_500])))

    def test_tml_event_aliases_are_mapped(self):
        self.assertEqual("montecarlo", compute_speed.NAMES["Monte Carlo"])
        self.assertEqual("cincinnati", compute_speed.NAMES["Cincinnati"])
        self.assertEqual("shanghai", compute_speed.NAMES["Shanghai"])
        self.assertEqual("shertogenbosch", compute_speed.NAMES["'s-Hertogenbosch"])
        self.assertEqual("lavercup", compute_speed.NAMES["Laver Cup"])
        self.assertEqual("marseille", compute_speed.NAMES["Marseille"])
        self.assertEqual("marseille", compute_speed.NAMES["Open 13 Provence"])
        self.assertEqual("stockholm", compute_speed.NAMES["Stockholm"])
        self.assertEqual("stockholm", compute_speed.NAMES["Stockholm Open"])
        self.assertEqual("nextgen", compute_speed.NAMES["Next Gen Finals"])
        self.assertEqual("nextgen", compute_speed.NAMES["Next Gen ATP Finals"])
        self.assertEqual("nextgen", compute_speed.NAMES["Next Generation ATP Finals"])
        self.assertEqual("adelaide", compute_speed.tournament_id("Adelaide International"))
        self.assertEqual("adelaide", compute_speed.tournament_id("ADELAIDE INTERNATIONAL"))
        self.assertEqual("rio", compute_speed.tournament_id("Rio Open"))
        self.assertEqual("rio", compute_speed.tournament_id("RIO OPEN"))

    def test_2025_archive_levels_and_serve_stats_are_accepted_for_missing_events(self):
        rows = []
        for event, level in (
            ("Marseille", "A"),
            ("Stockholm", "A"),
            ("Next Gen Finals", "F"),
        ):
            rows.append({
                "tourney_name": event,
                "tourney_level": level,
                "surface": "Hard",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": "100",
                "l_svpt": "100",
                "w_ace": "8",
                "l_ace": "7",
            })
        valid = compute_speed.valid_rows(rows)
        self.assertEqual(3, len(valid))
        self.assertEqual(
            {"marseille", "stockholm", "nextgen"},
            {compute_speed.NAMES[row["tourney_name"]] for row in valid},
        )


class SurfaceMatchedReturnerProfileTests(unittest.TestCase):
    @staticmethod
    def rows(surface_counts):
        rows = []
        index = 0
        for surface, match_count, opponent_ace_rate in surface_counts:
            for _ in range(match_count):
                index += 1
                rows.append({
                    "tourney_name": "Returner Profile Test",
                    "surface": surface,
                    "winner_id": "returner",
                    "winner_name": "Returner",
                    "loser_id": f"server-{index}",
                    "loser_name": f"Server {index}",
                    "w_svpt": 100,
                    "l_svpt": 100,
                    "w_ace": 8,
                    "l_ace": int(opponent_ace_rate * 100),
                })
        return rows

    def test_returner_profile_uses_same_clay_hard_grass_weighting_as_server(self):
        rows = self.rows([("Clay", 3, 0.10), ("Hard", 3, 0.20)])
        player = compute_speed.player_rates(rows)["returner"]

        clay = (30.0 + 150.0 * 0.09) / 450.0
        hard = (60.0 + 150.0 * 0.14) / 450.0
        self.assertAlmostEqual((1 / 3) * clay + (2 / 3) * hard, player["concede_rate"])
        self.assertAlmostEqual(0.15, player["pooled_concede_rate"])
        self.assertEqual(150.0, player["concede_rate_prior_points"])
        self.assertEqual("surface-qualified", player["concede_rate_source"])
        self.assertEqual(6, player["concede_matches"])
        self.assertEqual(600.0, player["concede_service_points"])

    def test_qualified_returner_surface_survives_small_secondary_surface_sample(self):
        rows = self.rows([("Clay", 3, 0.10), ("Hard", 2, 0.20)])
        player = compute_speed.player_rates(rows)["returner"]

        self.assertAlmostEqual((30.0 + 150.0 * 0.09) / 450.0, player["concede_rate"])
        self.assertAlmostEqual(0.14, player["pooled_concede_rate"])
        self.assertEqual("surface-qualified", player["concede_rate_source"])
        self.assertEqual(3, player["concede_matches"])
        self.assertEqual(300.0, player["concede_service_points"])

    def test_returner_profile_falls_back_to_pooled_rate_if_no_surface_qualifies(self):
        rows = self.rows([("Clay", 2, 0.10), ("Hard", 2, 0.20)])
        player = compute_speed.player_rates(rows)["returner"]

        self.assertAlmostEqual((60.0 + 150.0 * 0.115) / 550.0, player["concede_rate"])
        self.assertAlmostEqual(0.15, player["pooled_concede_rate"])
        self.assertEqual("pooled-fallback", player["concede_rate_source"])
        self.assertEqual(4, player["concede_matches"])
        self.assertEqual(400.0, player["concede_service_points"])
        self.assertEqual(150.0, player["concede_rate_prior_points"])

    def test_live_baseline_prefers_a_prior_surface_qualified_returner_over_current_pooled_fallback(self):
        current = {
            "player": profile(
                "player", "Player", serve_rate=0.10, concede_rate=0.07,
                concede_rate_source="pooled-fallback",
                clay_aces=15, clay_points=150, clay_matches=3, serve_matches=3,
                concede_matches=2, concede_service_points=200,
            )
        }
        prior = {
            "player": profile(
                "player", "Player", serve_rate=0.09, concede_rate=0.14,
                concede_rate_source="surface-qualified",
                concede_matches=4, concede_service_points=200,
                clay_aces=20, clay_points=200, clay_matches=4,
                clay_return_aces=28, clay_return_points=200, clay_return_matches=4,
            )
        }

        def fake_load(year):
            return [{"year": year}]

        def fake_player_rates(rows):
            return prior if rows[0]["year"] == 2025 else {}

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertAlmostEqual(0.10, live["player"]["serve_rate"])
        expected_history_return_rate = (
            (28.0 * 0.9) + 150.0 * ((15.0 + 20.0 * 0.9) / (150.0 + 200.0 * 0.9))
        ) / (200.0 * 0.9 + 150.0)
        self.assertAlmostEqual(expected_history_return_rate, live["player"]["concede_rate"])
        self.assertEqual("career-history-surface-shrunk", live["player"]["concede_rate_source"])
        self.assertEqual(2026, live["player"]["concede_rate_season"])
        self.assertEqual(4, live["player"]["concede_matches"])


class ReturnerProfileDedupeTests(unittest.TestCase):
    def test_surface_qualified_concede_profile_beats_newer_pooled_fallback(self):
        prior = profile(
            "old-id", "Alex Player", serve_rate=0.09, concede_rate=0.15,
            concede_rate_source="surface-qualified",
            concede_matches=6, concede_service_points=600,
            serve_matches=5,
        )
        prior.update({
            "serve_rate_season": 2025,
            "serve_rate_source": "prior-season-qualified",
            "serve_rate_sample_matches": 5,
            "serve_rate_sample_points": 500,
            "concede_rate_season": 2025,
        })
        current = profile(
            "new-id", "Alex Player", serve_rate=0.11, concede_rate=0.07,
            concede_rate_source="pooled-fallback",
            serve_matches=9,
        )
        current.update({
            "serve_rate_season": 2026,
            "serve_rate_source": "current-season-qualified",
            "serve_rate_sample_matches": 9,
            "serve_rate_sample_points": 900,
            "concede_rate_season": 2026,
        })

        result = compute_speed.dedupe_live_profiles([prior, current])

        self.assertEqual(1, len(result))
        self.assertAlmostEqual(0.11, result[0]["serve_rate"])
        self.assertAlmostEqual(0.15, result[0]["concede_rate"])
        self.assertEqual("surface-qualified", result[0]["concede_rate_source"])
        self.assertEqual(2025, result[0]["concede_rate_season"])


class InputValidationTests(unittest.TestCase):
    def test_rejects_non_finite_negative_or_impossible_ace_counts(self):
        base = {
            "tourney_level": "250", "winner_id": "p1", "loser_id": "p2",
            "w_svpt": "100", "l_svpt": "100", "w_ace": "8", "l_ace": "7",
            "surface": "Hard",
        }
        invalid = [
            dict(base, w_svpt="nan"),
            dict(base, w_ace="inf"),
            dict(base, w_ace="-1"),
            dict(base, w_ace="101"),
            dict(base, winner_id="p1", loser_id="p1"),
        ]
        self.assertEqual([], compute_speed.valid_rows(invalid))
        self.assertEqual(1, len(compute_speed.valid_rows([base])))


class DavisCupSourceWindowTests(unittest.TestCase):
    def test_historical_editions_and_validated_tml_2026_source_gate(self):
        for year in (2016, 2017, 2020, 2024):
            with self.subTest(year=year):
                self.assertTrue(is_supported_davis_cup_calculation_year("daviscup", year))

        for year in (1991, 2015, 2025, 2026, 2030):
            with self.subTest(year=year):
                self.assertFalse(is_supported_davis_cup_calculation_year("daviscup", year))

        self.assertTrue(is_supported_davis_cup_calculation_year(
            "daviscup", 2026, source=compute_speed.TML_DAVIS_CUP_SOURCE
        ))
        self.assertFalse(is_supported_davis_cup_calculation_year(
            "daviscup", 2026, source="TennisMyLife ATP Match Database"
        ))
        self.assertFalse(is_supported_davis_cup_calculation_year(
            "daviscup", 2026, source="Jeff Sackmann ATP archive (current-year fallback)"
        ))

        # The Davis Cup source window must not constrain ordinary ATP event history.
        self.assertTrue(is_supported_davis_cup_calculation_year("paris", 1991))
        self.assertTrue(is_supported_davis_cup_calculation_year("paris", 2026))

    def test_davis_cup_target_population_excludes_doubles(self):
        singles = {
            "tourney_name": "Davis Cup Finals QF: CZE vs CAN",
            "tourney_level": "D",
            "winner_id": "P1", "loser_id": "P2",
            "winner_name": "Tomas Machac", "loser_name": "Liam Draxl",
            "w_svpt": "70", "l_svpt": "90", "w_ace": "9", "l_ace": "6",
        }
        doubles = {
            **singles,
            "winner_id": "P3", "loser_id": "P4",
            "winner_name": "Adam Pavlasek / Patrik Rikl",
            "loser_name": "Felix Auger-Aliassime / Duncan Chan",
        }
        rows = compute_speed.valid_rows([singles, doubles])
        self.assertEqual(1, len(rows))
        self.assertEqual("Tomas Machac", rows[0]["winner_name"])

    def test_tml_ongoing_rows_merge_with_annual_rows_without_duplicates(self):
        annual = [{
            "tourney_id": "2026-M-DC-X", "tourney_date": "20261124", "match_num": "1",
            "winner_id": "P1", "loser_id": "P2", "score": "6-3 6-4",
            "w_ace": "4", "w_svpt": "70", "l_ace": "3", "l_svpt": "65",
        }]
        # Same rubber identity and score, but the ongoing snapshot contains corrected stats.
        ongoing = [
            {
                **annual[0],
                "w_ace": "8", "w_svpt": "72", "l_ace": "5", "l_svpt": "68",
            },
            {
                "tourney_id": "2026-M-DC-X", "tourney_date": "20261125", "match_num": "2",
                "winner_id": "P3", "loser_id": "P4", "score": "6-4 6-4",
            },
        ]
        merged, added = compute_speed.merge_davis_cup_rows(annual, ongoing)
        self.assertEqual(1, added)
        self.assertEqual(2, len(merged))
        self.assertEqual("P4", merged[-1]["loser_id"])
        self.assertEqual("8", merged[0]["w_ace"])
        self.assertEqual("72", merged[0]["w_svpt"])


class LeaveOneEventOutProductionTests(unittest.TestCase):
    def test_later_tournament_rows_do_not_change_earlier_event_rating(self):
        early_rows = []
        for event, event_date, aces in (
            ("Rome Masters", "20261014", 7),
            ("Paris Masters", "20261102", 8),
        ):
            for _ in range(12):
                early_rows.append({
                    "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                    "tourney_date": event_date, "winner_id": "p1", "loser_id": "p2",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": aces, "l_ace": aces,
                })
        future_rows = [{
            "tourney_name": "Tour Finals", "tourney_level": "F", "surface": "Hard",
            "tourney_date": "20261120", "winner_id": "p1", "loser_id": "p2",
            "w_svpt": 100, "l_svpt": 100, "w_ace": 35, "l_ace": 35,
        } for _ in range(12)]

        with patch.object(compute_speed, "build_live_profiles", return_value={}), \
             patch.object(compute_speed, "player_rates", return_value={}), \
             patch.object(compute_speed, "_prior_year_rows", return_value=[{
                 "tourney_name": "Prior Year", "tourney_level": "M", "surface": "Hard",
                 "winner_id": "p1", "loser_id": "p2", "w_svpt": 100, "l_svpt": 100,
                 "w_ace": 8, "l_ace": 8,
             }]), \
             patch.object(compute_speed, "ensure_event_profiles", return_value={
                 "tour_prior_sides": 0,
                 "career_history_server_sides": 0,
                 "career_history_returner_sides": 0,
                 "tour_prior_server_profiles": 0,
                 "tour_prior_returner_profiles": 0,
             }), \
             patch.object(compute_speed, "event_match_ratio_clusters",
                          side_effect=lambda rows, profiles, rate: [[1.0, 1.0] for _ in rows]):
            first = compute_speed.season_ratings(2026, early_rows)
            second = compute_speed.season_ratings(2026, early_rows + future_rows)
        self.assertEqual(first["paris"]["speed"], second["paris"]["speed"])
        self.assertEqual(first["rome"]["speed"], second["rome"]["speed"])

    def test_2026_davis_cup_is_target_only_and_requires_validated_tml_source(self):
        rows = []
        for event, level, aces in (
            ("Paris Masters", "M", 8),
            ("Rome Masters", "M", 9),
            ("Davis Cup Finals QF: CZE vs CAN", "D", 11),
        ):
            for match_num in range(10):
                rows.append({
                    "tourney_name": event, "tourney_level": level, "surface": "Hard",
                    "tourney_date": {"Paris Masters": "20261102", "Rome Masters": "20261014", "Davis Cup Finals QF: CZE vs CAN": "20261124"}[event],
                    "winner_id": "p1", "loser_id": "p2",
                    "winner_name": "Player One", "loser_name": "Player Two",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": aces, "l_ace": 7,
                    "match_num": str(match_num + 1),
                })

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}):
            without_source = compute_speed.season_ratings(2026, rows)
        self.assertNotIn("daviscup", without_source)

        real_player_rates = compute_speed.player_rates
        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}), \
             patch.object(compute_speed, "player_rates", wraps=real_player_rates) as profiles:
            with_source = compute_speed.season_ratings(
                2026, rows, source=compute_speed.TML_DAVIS_CUP_SOURCE,
            )

        self.assertIn("daviscup", with_source)
        self.assertEqual({"paris", "rome", "daviscup"}, set(with_source))
        # Every training invocation contains only the two standard ATP targets. The Davis Cup
        # target's 10 matches are observed for its own rating but never enter profile fitting.
        for call in profiles.call_args_list:
            self.assertTrue(all(row["tourney_level"] != "D" for row in call.args[0]))
        self.assertEqual(
            without_source["paris"]["speed"], with_source["paris"]["speed"],
        )
        self.assertEqual(
            without_source["rome"]["speed"], with_source["rome"]["speed"],
        )
        compute_speed._PROFILE_YEAR_CACHE.clear()

    def test_event_is_excluded_from_its_own_profiles_and_tour_anchor(self):
        rows = []
        for event in ("Paris Masters", "Rome Masters"):
            for _ in range(10):
                rows.append({
                    "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                    "tourney_date": "20260501" if event == "Rome Masters" else "20261001",
                    "winner_id": "p1", "loser_id": "p2",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": 8, "l_ace": 8,
                })

        real_player_rates = compute_speed.player_rates
        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}), \
             patch.object(compute_speed, "player_rates", wraps=real_player_rates) as profiles:
            ratings = compute_speed.season_ratings(2026, rows)

        self.assertEqual({"paris", "rome"}, set(ratings))
        self.assertEqual(4, profiles.call_count)
        for call in profiles.call_args_list:
            training_rows = call.args[0]
            event_names = {row["tourney_name"] for row in training_rows}
            self.assertEqual(1, len(event_names))
            self.assertEqual(10, len(training_rows))
            self.assertEqual(150.0, call.kwargs["prior_points"])

    def test_next_gen_is_rated_as_a_target_but_never_trains_or_normalises_standard_atp(self):
        standard_rows = []
        all_rows = []
        for event, level, aces in (
            ("Paris Masters", "M", 8),
            ("Rome Masters", "M", 8),
            # Short-set/no-ad format can produce a much higher per-service-point ace rate.
            ("Next Gen Finals", "F", 20),
        ):
            event_rows = [{
                "tourney_name": event, "tourney_level": level, "surface": "Hard",
                "tourney_date": {"Paris Masters": "20241104", "Rome Masters": "20241007", "Next Gen Finals": "20241218"}[event],
                "winner_id": "p1", "loser_id": "p2",
                "w_svpt": 100, "l_svpt": 100, "w_ace": aces, "l_ace": aces,
            } for _ in range(10)]
            all_rows.extend(event_rows)
            if event != "Next Gen Finals":
                standard_rows.extend(event_rows)

        real_player_rates = compute_speed.player_rates
        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}):
            standard_ratings = compute_speed.season_ratings(2024, standard_rows)

        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}), \
             patch.object(compute_speed, "player_rates", wraps=real_player_rates) as profiles:
            ratings = compute_speed.season_ratings(2024, all_rows)

        self.assertEqual({"paris", "rome", "nextgen"}, set(ratings))
        self.assertEqual(6, profiles.call_count)
        for call in profiles.call_args_list:
            training_rows = call.args[0]
            event_names = {row["tourney_name"] for row in training_rows}
            self.assertNotIn("Next Gen Finals", event_names)
        # A non-standard target can exceed 2.0, but must not pull standard events'
        # normaliser upwards. The full point estimate remains available rather than clipped.
        self.assertGreater(ratings["nextgen"]["speed"], 2.0)
        self.assertAlmostEqual(standard_ratings["paris"]["speed"], ratings["paris"]["speed"], places=12)
        self.assertAlmostEqual(standard_ratings["rome"]["speed"], ratings["rome"]["speed"], places=12)
        compute_speed._PROFILE_YEAR_CACHE.clear()


    def test_next_gen_is_excluded_from_historical_player_profile_training(self):
        rows = [
            {
                "tourney_name": event,
                "tourney_level": level,
                "surface": "Hard",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": 100,
                "l_svpt": 100,
                "w_ace": 8,
                "l_ace": 8,
            }
            for event, level in (("Next Gen Finals", "F"), ("Paris Masters", "M"))
        ]
        compute_speed._PROFILE_YEAR_CACHE.pop(2024, None)
        with patch.object(compute_speed, "load", return_value=rows), \
             patch.object(compute_speed, "player_rates", wraps=compute_speed.player_rates) as profiles:
            compute_speed._profile_year_rates(2024)
        self.assertEqual(1, profiles.call_count)
        trained_rows = profiles.call_args.args[0]
        self.assertEqual(["Paris Masters"], [row["tourney_name"] for row in trained_rows])
        compute_speed._PROFILE_YEAR_CACHE.pop(2024, None)


class SameSeasonIdentityCollisionTests(unittest.TestCase):
    def test_same_name_same_season_ids_are_excluded_even_when_rates_match(self):
        profiles = []
        for player_id in ("archive-id-one", "archive-id-two"):
            item = profile(
                player_id, "A Player", serve_rate=0.08, concede_rate=0.07,
                concede_rate_source="surface-qualified",
            )
            item.update({
                "serve_rate_season": 2026,
                "concede_rate_season": 2026,
                "serve_rate_source": "current-season-qualified",
                "serve_rate_sample_matches": 8,
                "serve_rate_sample_points": 500,
                "concede_service_points": 500,
            })
            profiles.append(item)

        deduped, diagnostics = compute_speed.dedupe_live_profiles_with_diagnostics(profiles)
        self.assertEqual([], deduped)
        self.assertEqual(1, diagnostics["ambiguousNameCollisionsExcluded"])


class LiveEventExclusionTests(unittest.TestCase):
    def test_fresh_manifest_excludes_active_current_season_events(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "live.json"
            manifest.write_text(json.dumps({"generated": "2026-11-07T23:00:00Z", "events": [
                {"t": "paris", "s": 2026, "status": "in_progress", "start": "2026-11-02", "end": "2026-11-08"},
                {"t": "vienna", "s": 2026, "status": "in_progress", "start": "2026-10-26", "end": "2026-11-01"},
            ]}), encoding="utf-8")
            result = compute_speed.live_event_exclusion(live_path=manifest, today=date(2026, 11, 8),
                now=datetime(2026, 11, 8, 5, 30, tzinfo=timezone.utc))
            self.assertTrue(result["fresh"])
            self.assertEqual(["paris"], result["eventIds"])

    def test_stale_manifest_is_not_trusted(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "live.json"
            manifest.write_text(json.dumps({"generated": "2026-10-20T00:00:00Z", "events": []}), encoding="utf-8")
            result = compute_speed.live_event_exclusion(live_path=manifest, today=date(2026, 11, 8),
                now=datetime(2026, 11, 8, 5, 30, tzinfo=timezone.utc))
            self.assertFalse(result["fresh"])

    def test_active_event_rows_are_removed_from_baseline_training(self):
        rows = [{"tourney_name": "Paris Masters"}, {"tourney_name": "Rome Masters"}]
        filtered = compute_speed.exclude_live_event_rows(rows, {"paris"})
        self.assertEqual(["Rome Masters"], [row["tourney_name"] for row in filtered])

    def test_scheduled_event_is_excluded_only_inside_its_live_date_window(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "live.json"
            manifest.write_text(json.dumps({
                "generated": "2026-12-09T00:00:00Z",
                "events": [
                    {
                        "t": "nextgen", "s": 2026, "status": "scheduled",
                        "start": "2026-12-09", "end": "2026-12-13",
                    },
                    {
                        "t": "paris", "s": 2026, "status": "scheduled",
                        "start": "2026-12-10", "end": "2026-12-15",
                    },
                ],
            }), encoding="utf-8")
            active = compute_speed.live_event_exclusion(
                live_path=manifest,
                today=date(2026, 12, 9),
                now=datetime(2026, 12, 9, 1, 0, tzinfo=timezone.utc),
            )
            self.assertTrue(active["fresh"])
            self.assertEqual(["nextgen"], active["eventIds"])


class ProductionIntervalAndSmallSampleTests(unittest.TestCase):
    def test_match_cluster_interval_is_reproducible(self):
        clusters = [[0.3, 0.5], [1.5, 1.7], [0.6, 0.8], [1.2, 1.4]]
        first = compute_speed.bootstrap_event_interval(clusters, 1.0, iterations=200, seed=17)
        second = compute_speed.bootstrap_event_interval(clusters, 1.0, iterations=200, seed=17)
        self.assertEqual(first, second)
        self.assertLess(first["low"], 1.0)
        self.assertGreater(first["high"], 1.0)

    def test_ten_to_nineteen_server_sides_are_primary_calc_but_not_normaliser_events(self):
        rows = []
        for event, count in (("Paris Masters", 5), ("Rome Masters", 5), ("Madrid Masters", 10)):
            for _ in range(count):
                rows.append({
                    "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                    "tourney_date": {"Paris Masters": "20261102", "Rome Masters": "20261014", "Madrid Masters": "20261021"}[event],
                    "winner_id": "p1", "loser_id": "p2",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": 8, "l_ace": 8,
                })

        def fake_clusters(event_rows, profiles, tour_rate):
            event = event_rows[0]["tourney_name"]
            ratio = {"Paris Masters": 4.0, "Rome Masters": 2.0, "Madrid Masters": 1.0}[event]
            return [[ratio, ratio] for _ in event_rows]

        coverage = {
            "tour_prior_sides": 0,
            "career_history_server_sides": 0,
            "career_history_returner_sides": 0,
            "tour_prior_server_profiles": 0,
            "tour_prior_returner_profiles": 0,
        }
        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}), \
             patch.object(compute_speed, "event_match_ratio_clusters", side_effect=fake_clusters), \
             patch.object(compute_speed, "ensure_event_profiles", return_value=coverage):
            ratings = compute_speed.season_ratings(2026, rows)

        self.assertEqual({"paris", "rome", "madrid"}, set(ratings))
        for event in ("paris", "rome"):
            self.assertIn("speed", ratings[event])
            self.assertNotIn("provisionalSpeed", ratings[event])
            self.assertEqual(10, ratings[event]["adjustedServerSides"])
            self.assertIsNotNone(ratings[event]["speedCiLow"])
            self.assertIsNotNone(ratings[event]["speedCiHigh"])
        self.assertEqual(4.0, ratings["paris"]["speed"])
        # The earlier Rome target has a prior-season-only anchor; Paris is later and
        # uses the completed Rome result as its pre-event anchor.
        self.assertGreater(ratings["rome"]["speed"], 0.0)
        # The twenty-side Madrid event alone supplies the normaliser.
        self.assertEqual(1.0, ratings["madrid"]["speed"])
        self.assertEqual(20, ratings["madrid"]["adjustedServerSides"])

    def test_one_adjusted_server_side_is_enough_for_a_completed_event_calc(self):
        rows = []
        for event in ("Paris Masters", "Rome Masters"):
            rows.append({
                "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                "tourney_date": "20261102" if event == "Paris Masters" else "20261014",
                "winner_id": "p1", "loser_id": "p2",
                "w_svpt": 100, "l_svpt": 100, "w_ace": 8, "l_ace": 8,
            })

        def one_side(event_rows, profiles, tour_rate):
            return [[1.5 if event_rows[0]["tourney_name"] == "Paris Masters" else 0.5]]

        coverage = {
            "tour_prior_sides": 0,
            "career_history_server_sides": 0,
            "career_history_returner_sides": 0,
            "tour_prior_server_profiles": 0,
            "tour_prior_returner_profiles": 0,
        }
        compute_speed._PROFILE_YEAR_CACHE.clear()
        with patch.object(compute_speed, "_profile_year_rates", return_value={}), \
             patch.object(compute_speed, "event_match_ratio_clusters", side_effect=one_side), \
             patch.object(compute_speed, "ensure_event_profiles", return_value=coverage):
            ratings = compute_speed.season_ratings(2026, rows)

        self.assertEqual({"paris", "rome"}, set(ratings))
        self.assertEqual(1, ratings["paris"]["adjustedServerSides"])
        self.assertEqual(1, ratings["rome"]["adjustedServerSides"])
        self.assertEqual(1.5, ratings["paris"]["speed"])
        self.assertGreater(ratings["rome"]["speed"], 0.0)
        # Do not publish a degenerate confidence interval from a single match cluster.
        self.assertIsNone(ratings["paris"]["speedCiLow"])
        self.assertIsNone(ratings["paris"]["speedCiHigh"])





class HistoricalEventAliasRegressionTests(unittest.TestCase):
    def test_adelaide_archive_editions_map_to_one_annual_event(self):
        self.assertEqual("adelaide", compute_speed.tournament_id("Adelaide 1"))
        self.assertEqual("adelaide", compute_speed.tournament_id("Adelaide 2"))
        self.assertEqual("adelaide", compute_speed.tournament_id("Adelaide International 1"))
        self.assertEqual("adelaide", compute_speed.tournament_id("Adelaide International 2"))

    def test_rio_2020_archive_label_maps_to_existing_rio_event(self):
        self.assertEqual("rio", compute_speed.tournament_id("ATP Rio de Janeiro"))
        self.assertEqual("rio", compute_speed.tournament_id("Rio De Janeiro"))


if __name__ == "__main__":
    unittest.main()
