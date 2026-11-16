import unittest

from cpi_snapshot import select_cpi_readings


class CpiSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.fallback = [
            ("paris", 2026, 50.0),
            ("rome", 2026, 25.4),
            ("atpfinals", 2025, 40.1),
        ]
        self.source_rows = [
            {"t": f"event-{year}-{index}", "s": year, "cpi": 30.0 + index / 10}
            for year in range(2012, 2026)
            for index in range(4)
        ]
        self.source_rows.extend([
            {"t": "paris", "s": 2026, "cpi": 50.0},
            {"t": "atpfinals", "s": 2026, "cpi": 45.8},
            {"t": "indianwells", "s": 2026, "cpi": 39.3},
            {"t": "miami", "s": 2026, "cpi": 39.2},
            {"t": "cincinnati", "s": 2026, "cpi": 40.4},
        ])

    def test_complete_source_replaces_fallback_and_drops_withdrawn_rows(self):
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"generated": "2026-11-16T14:33:49Z", "readings": self.source_rows},
            2026,
        )
        by_key = {(tournament, season): value for tournament, season, value in rows}
        self.assertTrue(authoritative)
        self.assertEqual(45.8, by_key[("atpfinals", 2026)])
        self.assertEqual(50.0, by_key[("paris", 2026)])
        self.assertNotIn(("rome", 2026), by_key)
        self.assertNotIn(("atpfinals", 2025), by_key)

    def test_incomplete_source_keeps_last_known_fallback(self):
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"readings": self.source_rows[:10]},
            2026,
        )
        self.assertFalse(authoritative)
        self.assertEqual(
            self.fallback,
            rows,
        )

    def test_source_without_enough_current_season_rows_is_not_authoritative(self):
        mostly_historical = [
            {"t": f"old-{index}", "s": 2025, "cpi": 30.0 + index / 10}
            for index in range(60)
        ]
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"readings": mostly_historical},
            2026,
        )
        self.assertFalse(authoritative)
        self.assertEqual(self.fallback, rows)

    def test_invalid_source_row_falls_back_atomically(self):
        invalid = list(self.source_rows)
        invalid.append({"t": "broken", "s": 2026, "cpi": "not-a-number"})
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"readings": invalid},
            2026,
        )
        self.assertFalse(authoritative)
        self.assertEqual(self.fallback, rows)


if __name__ == "__main__":
    unittest.main()
