import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_surface_backfill_2026_forward as forward
import audit_lower_tier_profile_backfill as backfill


def row(
    *,
    tournament_id="2026-001",
    tournament_name="Sample ATP",
    date="20260601",
    match_num="1",
    level="250",
    round_name="R32",
    surface="Clay",
    winner="T1",
    loser="T2",
    winner_aces=10,
    loser_aces=6,
    winner_points=100,
    loser_points=100,
):
    return {
        "tourney_id": tournament_id,
        "tourney_name": tournament_name,
        "tourney_date": date,
        "match_num": str(match_num),
        "tourney_level": level,
        "round": round_name,
        "surface": surface,
        "winner_id": winner,
        "loser_id": loser,
        "w_svpt": str(winner_points),
        "l_svpt": str(loser_points),
        "w_ace": str(winner_aces),
        "l_ace": str(loser_aces),
    }


class SourceMergeTests(unittest.TestCase):
    def test_static_and_ongoing_rows_are_deduplicated_by_event_date_match(self):
        static = [
            row(tournament_id="2026-352", date="20261102", tournament_name="Paris", winner="A", loser="B"),
            row(tournament_id="2026-352", date="20261102", tournament_name="Paris", match_num="2", winner="C", loser="D"),
        ]
        ongoing = [
            row(tournament_id="2026-352", date="20261102", tournament_name="Paris", match_num="2", winner="C", loser="D"),
            row(tournament_id="2026-352", date="20261103", tournament_name="Paris", match_num="3", winner="E", loser="F"),
        ]
        merged, duplicates = forward.deduplicate_rows(static, ongoing)
        self.assertEqual(3, len(merged))
        self.assertEqual(1, duplicates)

    def test_after_may_17_counts_only_post_cutoff_valid_rows(self):
        rows = [
            row(date="20260517"),
            row(date="20260518", tournament_id="2026-002"),
            row(date="20261107", tournament_id="2026-003"),
        ]
        summary = forward.source_row_summary(
            rows,
            rows,
            {"name": "fixture", "downloaded_sha256": "abc"},
        )
        self.assertEqual(3, summary["valid_rows"])
        self.assertEqual(2, summary["post_2026_05_17_valid_rows"])
        self.assertEqual("20261107", summary["last_valid_tournament_date_key"])


class IdentityCrosswalkTests(unittest.TestCase):
    def test_only_unique_full_name_matches_are_crosswalked_for_target_ids(self):
        target = [
            {
                "winner_id": "A0E2", "winner_name": "Carlos Alcaraz",
                "loser_id": "D643", "loser_name": "Novak Djokovic",
            }
        ]
        database = [
            {"id": "A0E2", "player": "Carlos Alcaraz", "atpname": "Carlos Alcaraz"},
            {"id": "D643", "player": "Novak Djokovic", "atpname": "Novak Djokovic"},
            {"id": "OFF_TARGET", "player": "Other Player", "atpname": "Other Player"},
        ]
        historical = {
            "carlosalcaraz": {"128034"},
            "novakdjokovic": {"104527"},
            "otherplayer": {"999999"},
        }
        mapping, diagnostics = forward.build_tml_identity_crosswalk(
            target, database, historical
        )
        self.assertEqual(
            {"A0E2": "128034", "D643": "104527"},
            mapping,
        )
        self.assertEqual(2, diagnostics["target_tml_player_ids"])
        self.assertEqual(2, diagnostics["uniquely_crosswalked_to_archive_id"])

    def test_ambiguous_historical_name_is_excluded(self):
        target = [
            {
                "winner_id": "AX01", "winner_name": "Alex Lee",
                "loser_id": "KNOWN", "loser_name": "Known Player",
            }
        ]
        database = [
            {"id": "AX01", "player": "Alex Lee", "atpname": "Alex Lee"},
            {"id": "KNOWN", "player": "Known Player", "atpname": "Known Player"},
        ]
        historical = {
            "alexlee": {"101", "202"},
            "knownplayer": {"303"},
        }
        mapping, diagnostics = forward.build_tml_identity_crosswalk(
            target, database, historical
        )
        self.assertEqual({"KNOWN": "303"}, mapping)
        self.assertEqual(1, diagnostics["ambiguous_name_crosswalks"])
        self.assertEqual(1, diagnostics["uniquely_crosswalked_to_archive_id"])

    def test_remap_preserves_unmatched_tml_ids_and_maps_unique_ids(self):
        rows = [
            row(winner="A0E2", loser="UNKNOWN"),
        ]
        result = forward.remap_tml_rows_to_archive_ids(
            rows, {"A0E2": "128034"}
        )
        self.assertEqual("128034", result[0]["winner_id"])
        self.assertEqual("UNKNOWN", result[0]["loser_id"])


class ChronologicalProfileTests(unittest.TestCase):
    def setUp(self):
        historical = [
            row(
                tournament_id="2025-001", tournament_name="Prior ATP",
                date="20251001", winner="P1", loser="P2",
                winner_aces=8, loser_aces=6, level="250",
            )
        ]
        self.main_by_year = {2025: backfill.aggregate_rows(historical)}
        self.lower_by_year = {}

        self.target = [
            row(
                tournament_id="2026-999", tournament_name="Target ATP",
                date="20260601", winner="T1", loser="T2",
                winner_aces=22, loser_aces=6,
            )
        ]
        self.current_atp = [
            row(
                tournament_id="2026-100", tournament_name="Earlier ATP",
                date="20260105", winner="T1", loser="T2",
                winner_aces=12, loser_aces=6,
            ),
            *self.target,
        ]
        self.early_challenger = [
            row(
                tournament_id="2026-200", tournament_name="Earlier Challenger",
                date="20260104", level="C", round_name="R32",
                winner="T1", loser="T3", winner_aces=40, loser_aces=5,
            )
        ]
        self.same_week_challenger = [
            row(
                tournament_id="2026-201", tournament_name="Same-week Challenger",
                date="20260601", level="C", round_name="R32",
                winner="T1", loser="T3", winner_aces=95, loser_aces=1,
            )
        ]
        self.future_qualifying = [
            row(
                tournament_id="2026-300", tournament_name="Future qualifying",
                date="20260701", level="250", round_name="Q1",
                winner="T1", loser="T3", winner_aces=99, loser_aces=1,
            )
        ]
        self.crosswalk = {"T1": "P1", "T2": "P2", "T3": "P3"}

    def test_same_week_and_future_lower_tier_rows_cannot_change_target_profile(self):
        with_earlier, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger,
            [],
            1.0,
            0.0,
        )
        with_same_week_and_future, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger + self.same_week_challenger,
            self.future_qualifying,
            1.0,
            1.0,
        )
        self.assertEqual(
            with_earlier["2026-999"],
            with_same_week_and_future["2026-999"],
        )

    def test_earlier_same_season_challenger_history_can_change_player_predictions(self):
        without_lower, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            [],
            [],
            0.0,
            0.0,
        )
        with_earlier, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger,
            [],
            1.0,
            0.0,
        )
        baseline = without_lower["2026-999"]
        augmented = with_earlier["2026-999"]
        self.assertEqual(baseline["sides"], augmented["sides"])
        self.assertNotAlmostEqual(
            baseline["predicted_aces"],
            augmented["predicted_aces"],
        )

    def test_all_match_days_are_accumulated_into_one_tournament_cluster(self):
        target_rows = [
            row(
                tournament_id="2026-999", tournament_name="Multi-day ATP",
                date="20260601", winner="T1", loser="T2",
                winner_aces=20, loser_aces=5,
            ),
            row(
                tournament_id="2026-999", tournament_name="Multi-day ATP",
                date="20260602", match_num="2", winner="T3", loser="T4",
                winner_aces=15, loser_aces=7,
            ),
        ]
        current_main = [
            row(
                tournament_id="2026-100", tournament_name="Earlier ATP",
                date="20260105", winner="T1", loser="T2",
                winner_aces=12, loser_aces=6,
            ),
            *target_rows,
        ]
        result, post = forward.evaluate_tml_target_rows(
            target_rows,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            current_main,
            [],
            [],
            0.0,
            0.0,
        )
        self.assertEqual(4, result["2026-999"]["sides"])
        self.assertEqual(4, post["2026-999"]["sides"])
        self.assertEqual(400.0, post["2026-999"]["points"])

    def test_target_event_rows_are_not_used_in_its_own_profile(self):
        baseline, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger,
            [],
            1.0,
            0.0,
        )
        modified_target = [
            row(
                tournament_id="2026-999", tournament_name="Target ATP",
                date="20260601", winner="T1", loser="T2",
                winner_aces=80, loser_aces=70,
            )
        ]
        modified, _ = forward.evaluate_tml_target_rows(
            modified_target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger,
            [],
            1.0,
            0.0,
        )
        self.assertNotEqual(
            baseline["2026-999"]["aces"],
            modified["2026-999"]["aces"],
        )
        # Predictions are exactly the same because own-event target rows are excluded.
        self.assertAlmostEqual(
            baseline["2026-999"]["predicted_aces"],
            modified["2026-999"]["predicted_aces"],
        )

    def test_population_prior_is_unchanged_by_same_season_lower_tier_data(self):
        no_lower, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            [],
            [],
            0.0,
            0.0,
        )
        with_lower, _ = forward.evaluate_tml_target_rows(
            self.target,
            self.crosswalk,
            self.main_by_year,
            self.lower_by_year,
            self.current_atp,
            self.early_challenger,
            [],
            1.0,
            0.0,
        )
        # The only changes are player-specific predictions; side / point denominators
        # and the ATP-only population anchor are not supplied from lower-tier inputs.
        self.assertEqual(no_lower["2026-999"]["sides"], with_lower["2026-999"]["sides"])
        self.assertEqual(no_lower["2026-999"]["points"], with_lower["2026-999"]["points"])


class SurfacePolicyTests(unittest.TestCase):
    def test_unknown_and_under_sampled_surfaces_default_to_atp_only(self):
        arms = backfill.arm_grid()
        clusters = backfill.empty_cluster("unknown")
        clusters["sides"] = 100
        results = {
            arm: {2012: {"event-a": clusters}}
            for arm in arms
        }
        policy, diagnostics = forward.select_surface_policy(
            results, [2012], arms
        )
        self.assertEqual("atp_only", policy["unknown"])
        self.assertEqual(
            "insufficient development observations; defaulted to ATP-only",
            diagnostics["unknown"]["selection_reason"],
        )


if __name__ == "__main__":
    unittest.main()
