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
            concede_rate * concede_service_points
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
        "clay_return_aces": 0.0,
        "clay_return_points": 0.0,
        "clay_return_matches": 0,
        "hard_grass_return_aces": 0.0,
        "hard_grass_return_points": 0.0,
        "hard_grass_return_matches": 0,
    }


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

        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertEqual(0.11, live["p1"]["serve_rate"])
        self.assertEqual("current-season-qualified", live["p1"]["serve_rate_source"])
        self.assertEqual(2026, live["p1"]["serve_rate_season"])

        self.assertEqual(0.095, live["p2"]["serve_rate"])
        self.assertEqual("prior-season-qualified", live["p2"]["serve_rate_source"])
        self.assertEqual(2025, live["p2"]["serve_rate_season"])

        self.assertIsNotNone(live["p3"]["serve_rate"])
        self.assertGreaterEqual(live["p3"]["serve_rate"], 0.0)
        self.assertLessEqual(live["p3"]["serve_rate"], 1.0)
        self.assertEqual("current-season-shrunk", live["p3"]["serve_rate_source"])
        self.assertEqual(2026, live["p3"]["serve_rate_season"])
        self.assertEqual(150.0, live["p3"]["serve_rate_prior_points"])

        self.assertEqual(0.10, live["p5"]["serve_rate"])
        self.assertEqual("prior-season-qualified", live["p5"]["serve_rate_source"])
        self.assertEqual(2025, live["p5"]["serve_rate_season"])

        self.assertIsNotNone(live["p6"]["serve_rate"])
        self.assertEqual("prior-season-shrunk", live["p6"]["serve_rate_source"])
        self.assertEqual(2025, live["p6"]["serve_rate_season"])

        # Never manufacture a rate if the archive contains no serve-side sample at all.
        self.assertIsNone(live["p4"]["serve_rate"])
        self.assertIsNone(live["p4"]["serve_rate_source"])

    def test_uses_only_the_ten_most_recent_prior_seasons(self):
        current = {
            "p1": profile("p1", "Current", serve_rate=None, clay_aces=1, clay_points=20, clay_matches=1),
        }
        visited = []

        def fake_load(year):
            visited.append(year)
            return [{"year": year}]

        def fake_player_rates(rows):
            return {}

        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            compute_speed.build_live_profiles(2026, current)

        self.assertEqual(list(range(2025, 2015, -1)), visited)


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
                clay_aces=12, clay_points=150, clay_matches=2,
            )
        }
        prior = {
            "player": profile(
                "player", "Player", serve_rate=0.09, concede_rate=0.14,
                concede_rate_source="surface-qualified",
                concede_matches=6, concede_service_points=600,
                clay_aces=20, clay_points=200, clay_matches=4,
            )
        }

        with patch.object(compute_speed, "load", return_value=[{"year": 2025}]), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", return_value=prior):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertAlmostEqual(0.10, live["player"]["serve_rate"])
        self.assertAlmostEqual(0.14, live["player"]["concede_rate"])
        self.assertEqual("surface-qualified", live["player"]["concede_rate_source"])
        self.assertEqual(2025, live["player"]["concede_rate_season"])
        self.assertEqual(6, live["player"]["concede_matches"])


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


class LeaveOneEventOutProductionTests(unittest.TestCase):
    def test_event_is_excluded_from_its_own_profiles_and_tour_anchor(self):
        rows = []
        for event in ("Paris Masters", "Rome Masters"):
            for _ in range(10):
                rows.append({
                    "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                    "winner_id": "p1", "loser_id": "p2",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": 8, "l_ace": 8,
                })

        real_player_rates = compute_speed.player_rates
        with patch.object(compute_speed, "player_rates", wraps=real_player_rates) as profiles:
            ratings = compute_speed.season_ratings(2026, rows)

        self.assertEqual({"paris", "rome"}, set(ratings))
        self.assertEqual(2, profiles.call_count)
        for call in profiles.call_args_list:
            training_rows = call.args[0]
            event_names = {row["tourney_name"] for row in training_rows}
            self.assertEqual(1, len(event_names))
            self.assertEqual(10, len(training_rows))
            self.assertEqual(150.0, call.kwargs["prior_points"])


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


class ProductionIntervalAndProvisionalTests(unittest.TestCase):
    def test_match_cluster_interval_is_reproducible(self):
        clusters = [[0.3, 0.5], [1.5, 1.7], [0.6, 0.8], [1.2, 1.4]]
        first = compute_speed.bootstrap_event_interval(clusters, 1.0, iterations=200, seed=17)
        second = compute_speed.bootstrap_event_interval(clusters, 1.0, iterations=200, seed=17)
        self.assertEqual(first, second)
        self.assertLess(first["low"], 1.0)
        self.assertGreater(first["high"], 1.0)

    def test_ten_to_nineteen_server_sides_are_provisional_not_primary(self):
        rows = []
        for event, count in (("Paris Masters", 5), ("Rome Masters", 5), ("Madrid Masters", 10)):
            for _ in range(count):
                rows.append({
                    "tourney_name": event, "tourney_level": "M", "surface": "Hard",
                    "winner_id": "p1", "loser_id": "p2",
                    "w_svpt": 100, "l_svpt": 100, "w_ace": 8, "l_ace": 8,
                })
        ratings = compute_speed.season_ratings(2026, rows)
        self.assertEqual({"paris", "rome", "madrid"}, set(ratings))
        for event in ("paris", "rome"):
            self.assertNotIn("speed", ratings[event])
            self.assertEqual(10, ratings[event]["provisionalServerSides"])
            self.assertIn("provisionalSpeed", ratings[event])
            self.assertIn("provisionalCiLow", ratings[event])
            self.assertIn("provisionalCiHigh", ratings[event])
        self.assertIn("speed", ratings["madrid"])
        self.assertEqual(20, ratings["madrid"]["adjustedServerSides"])
        self.assertIn("speedCiLow", ratings["madrid"])
        self.assertIn("speedCiHigh", ratings["madrid"])




if __name__ == "__main__":
    unittest.main()
