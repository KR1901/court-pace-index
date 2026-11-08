import unittest
from unittest.mock import patch

import audit_event_population as audit


class EventPopulationAuditTests(unittest.TestCase):
    def row(self, **updates):
        row = {
            "tourney_level": "250",
            "winner_id": "1",
            "loser_id": "2",
            "w_svpt": "50",
            "l_svpt": "48",
            "w_ace": "5",
            "l_ace": "3",
        }
        row.update(updates)
        return row

    def assert_same_eligibility_as_production(self, row):
        self.assertEqual(
            audit.reason(row) is None,
            bool(audit.compute_speed.valid_rows([row])),
        )

    def test_merge_match_rows_appends_only_new_event_match_pairs(self):
        existing = [
            self.row(tourney_id="2026-352", tourney_date="20261102", match_num="1"),
            self.row(tourney_id="2026-352", tourney_date="20261102", match_num="2"),
        ]
        incoming = [
            self.row(tourney_id="2026-352", tourney_date="20261102", match_num="2"),
            self.row(tourney_id="2026-352", tourney_date="20261103", match_num="3"),
        ]

        merged, counts = audit.merge_match_rows(existing, incoming)

        self.assertEqual(3, len(merged))
        self.assertEqual(1, counts["appended_rows"])
        self.assertEqual(1, counts["duplicate_rows"])

    def test_valid_main_draw_match_is_eligible(self):
        row = self.row()
        self.assertIsNone(audit.reason(row))
        self.assert_same_eligibility_as_production(row)

    def test_qualifying_level_is_not_main_tour(self):
        row = self.row(tourney_level="Q")
        self.assertEqual(audit.reason(row), "ineligible_tournament_level")
        self.assert_same_eligibility_as_production(row)

    def test_missing_service_stats_are_reported(self):
        row = self.row(w_ace="")
        self.assertEqual(audit.reason(row), "missing_or_non_numeric_serve_stats")
        self.assert_same_eligibility_as_production(row)

    def test_non_positive_service_points_are_rejected(self):
        row = self.row(w_svpt="0")
        self.assertEqual(audit.reason(row), "non_positive_points_or_negative_aces")
        self.assert_same_eligibility_as_production(row)

    def test_negative_aces_are_rejected(self):
        row = self.row(l_ace="-1")
        self.assertEqual(audit.reason(row), "non_positive_points_or_negative_aces")
        self.assert_same_eligibility_as_production(row)

    def test_aces_above_service_points_are_rejected(self):
        row = self.row(w_ace="51")
        self.assertEqual(audit.reason(row), "aces_exceed_service_points")
        self.assert_same_eligibility_as_production(row)

    def test_missing_player_id_is_reported(self):
        row = self.row(winner_id="")
        self.assertEqual(audit.reason(row), "missing_or_conflicting_player_ids")
        self.assert_same_eligibility_as_production(row)

    def test_conflicting_player_ids_are_rejected(self):
        row = self.row(loser_id="1")
        self.assertEqual(audit.reason(row), "missing_or_conflicting_player_ids")
        self.assert_same_eligibility_as_production(row)

    def test_profile_coverage_reports_unknown_event_without_inventing_coverage(self):
        result = audit.model_profile_coverage([], None)
        self.assertIsNone(result["rateable_server_sides"])
        self.assertIsNone(result["missing_server_profile_before_fallback"])

    def test_profile_coverage_uses_long_history_profiles_for_both_event_sides(self):
        rows = [
            {
                "tourney_name": "Montpellier", "tourney_level": "250", "surface": "Hard",
                "winner_id": "winner", "winner_name": "Taylor Fritz",
                "loser_id": "loser", "loser_name": "Hubert Hurkacz",
                "w_svpt": "80", "l_svpt": "82", "w_ace": "10", "l_ace": "8",
            },
            {
                "tourney_name": "Adelaide", "tourney_level": "250", "surface": "Hard",
                "winner_id": "other1", "winner_name": "Other Player One",
                "loser_id": "other2", "loser_name": "Other Player Two",
                "w_svpt": "100", "l_svpt": "100", "w_ace": "10", "l_ace": "10",
            },
        ]
        profiles = {
            "winner": {
                "id": "winner", "name": "Taylor Fritz",
                "serve_rate": 0.12, "concede_rate": 0.08,
                "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate_source": "career-history-pooled-shrunk",
            },
            "loser": {
                "id": "loser", "name": "Hubert Hurkacz",
                "serve_rate": 0.15, "concede_rate": 0.09,
                "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate_source": "career-history-surface-shrunk",
            },
        }
        with patch.object(audit.compute_speed, "build_live_profiles", return_value=profiles):
            result = audit.model_profile_coverage(rows, "montpellier", 2026)

        self.assertEqual(2, result["rateable_server_sides"])
        self.assertEqual(0, result["tour_prior_sides"])
        self.assertEqual(2, result["career_history_server_sides"])
        self.assertEqual(2, result["career_history_returner_sides"])
        self.assertEqual(35, result["profile_source_window_years"])

    def test_profile_coverage_uses_explicit_tour_prior_for_true_profile_gaps(self):
        rows = [
            {
                "tourney_name": "Montpellier", "tourney_level": "250", "surface": "Hard",
                "winner_id": "winner", "winner_name": "Unseen Player One",
                "loser_id": "loser", "loser_name": "Unseen Player Two",
                "w_svpt": "80", "l_svpt": "82", "w_ace": "10", "l_ace": "8",
            },
            {
                "tourney_name": "Adelaide", "tourney_level": "250", "surface": "Hard",
                "winner_id": "other1", "winner_name": "Other Player One",
                "loser_id": "other2", "loser_name": "Other Player Two",
                "w_svpt": "100", "l_svpt": "100", "w_ace": "10", "l_ace": "10",
            },
        ]
        with patch.object(audit.compute_speed, "build_live_profiles", return_value={}):
            result = audit.model_profile_coverage(rows, "montpellier", 2026)

        self.assertEqual(2, result["rateable_server_sides"])
        self.assertEqual(2, result["tour_prior_sides"])
        self.assertEqual(2, result["tour_prior_server_profiles"])
        self.assertEqual(2, result["tour_prior_returner_profiles"])
        self.assertEqual(2, result["missing_server_profile_before_fallback"])
        self.assertEqual(2, result["missing_returner_profile_before_fallback"])
        details = result["tour_prior_fallback_details"]
        self.assertEqual(4, len(details))
        self.assertEqual(
            {"no_player_profile_in_history"},
            {item["reason"] for item in details},
        )

    def test_ambiguous_name_matches_are_not_reported_as_absent_history(self):
        rows = [
            {
                "tourney_name": "Montpellier", "tourney_level": "250", "surface": "Hard",
                "winner_id": "target", "winner_name": "Alex Lee",
                "loser_id": "known", "loser_name": "Known Player",
                "w_svpt": "80", "l_svpt": "82", "w_ace": "10", "l_ace": "8",
            },
            {
                "tourney_name": "Adelaide", "tourney_level": "250", "surface": "Hard",
                "winner_id": "other1", "winner_name": "Other Player One",
                "loser_id": "other2", "loser_name": "Other Player Two",
                "w_svpt": "100", "l_svpt": "100", "w_ace": "10", "l_ace": "10",
            },
        ]
        profiles = {
            "archive-1": {"id": "archive-1", "name": "Alex Lee", "serve_rate": 0.11, "concede_rate": 0.09},
            "archive-2": {"id": "archive-2", "name": "Alex Lee", "serve_rate": 0.12, "concede_rate": 0.10},
            "known": {
                "id": "known", "name": "Known Player",
                "serve_rate": 0.13, "concede_rate": 0.08,
                "serve_rate_source": "career-history-surface-shrunk",
                "concede_rate_source": "career-history-surface-shrunk",
            },
        }
        with patch.object(audit.compute_speed, "build_live_profiles", return_value=profiles):
            result = audit.model_profile_coverage(rows, "montpellier", 2026)

        self.assertEqual(2, result["rateable_server_sides"])
        self.assertEqual(2, result["tour_prior_sides"])
        details = result["tour_prior_fallback_details"]
        self.assertEqual(2, len(details))
        self.assertEqual(
            {"ambiguous_name_match"},
            {item["reason"] for item in details},
        )
        self.assertTrue(all(item["name_candidate_count"] == 2 for item in details))

    def test_profile_record_without_role_rate_is_distinguished_from_no_profile(self):
        rows = [
            {
                "tourney_name": "Montpellier", "tourney_level": "250", "surface": "Hard",
                "winner_id": "winner", "winner_name": "Taylor Fritz",
                "loser_id": "loser", "loser_name": "Hubert Hurkacz",
                "w_svpt": "80", "l_svpt": "82", "w_ace": "10", "l_ace": "8",
            },
            {
                "tourney_name": "Adelaide", "tourney_level": "250", "surface": "Hard",
                "winner_id": "other1", "winner_name": "Other Player One",
                "loser_id": "other2", "loser_name": "Other Player Two",
                "w_svpt": "100", "l_svpt": "100", "w_ace": "10", "l_ace": "10",
            },
        ]
        profiles = {
            "winner": {
                "id": "winner", "name": "Taylor Fritz",
                "serve_rate": None, "concede_rate": None,
            },
            "loser": {
                "id": "loser", "name": "Hubert Hurkacz",
                "serve_rate": None, "concede_rate": None,
            },
        }
        with patch.object(audit.compute_speed, "build_live_profiles", return_value=profiles):
            result = audit.model_profile_coverage(rows, "montpellier", 2026)

        self.assertEqual(2, result["rateable_server_sides"])
        self.assertEqual(2, result["tour_prior_sides"])
        self.assertEqual(2, result["missing_server_profile_before_fallback"])
        self.assertEqual(2, result["missing_returner_profile_before_fallback"])
        details = result["tour_prior_fallback_details"]
        self.assertEqual(4, len(details))
        self.assertTrue(all(item["had_player_profile"] for item in details))
        self.assertEqual(
            {"player_profile_exists_without_rate"},
            {item["reason"] for item in details},
        )


if __name__ == "__main__":
    unittest.main()
