import unittest

import audit_event_population as audit


class EventPopulationAuditTests(unittest.TestCase):
    def row(self, **updates):
        row = {"tourney_level": "250", "winner_id": "1", "loser_id": "2",
               "w_svpt": "50", "l_svpt": "48", "w_ace": "5", "l_ace": "3"}
        row.update(updates)
        return row

    def test_valid_main_draw_match_is_eligible(self):
        self.assertIsNone(audit.reason(self.row()))

    def test_qualifying_level_is_not_main_tour(self):
        self.assertEqual(audit.reason(self.row(tourney_level="Q")), "ineligible_tournament_level")

    def test_missing_service_stats_are_reported(self):
        self.assertEqual(audit.reason(self.row(w_ace="")), "missing_or_non_numeric_serve_stats")

    def test_invalid_ace_count_is_reported(self):
        self.assertEqual(audit.reason(self.row(w_ace="51")), "invalid_ace_counts")

    def test_missing_player_id_is_reported(self):
        self.assertEqual(audit.reason(self.row(winner_id="")), "missing_or_conflicting_player_ids")


if __name__ == "__main__":
    unittest.main()
