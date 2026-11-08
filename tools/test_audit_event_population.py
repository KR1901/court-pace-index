import unittest

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
        self.assertIsNone(result["missing_server_profile"])


if __name__ == "__main__":
    unittest.main()
