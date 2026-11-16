import unittest

from cpi_snapshot import select_cpi_readings


class CpiSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.fallback = [
            ("paris", 2026, 50.0),
            ("rome", 2026, 25.4),
            ("atpfinals", 2025, 40.4),
            ("atpfinals", 2026, 45.8),
            ("usopen", 2024, 42.8),
        ]
        self.sorted_fallback = sorted(self.fallback)

        # 97 historical source readings plus the nine values currently published
        # for 2026 gives the Court Speed table's current total of 106 readings.
        self.source_rows = [
            {"t": f"event-{index}", "s": 2012 + (index % 14), "cpi": 30.0 + (index % 30) / 10}
            for index in range(96)
        ]
        self.source_rows.append({"t": "atpfinals", "s": 2025, "cpi": 40.1})
        self.source_rows.extend([
            {"t": "indianwells", "s": 2026, "cpi": 39.3},
            {"t": "miami", "s": 2026, "cpi": 39.2},
            {"t": "montecarlo", "s": 2026, "cpi": 27.1},
            {"t": "madrid", "s": 2026, "cpi": 29.8},
            {"t": "rome", "s": 2026, "cpi": 25.4},
            {"t": "canada", "s": 2026, "cpi": 36.5},
            {"t": "cincinnati", "s": 2026, "cpi": 40.4},
            {"t": "shanghai", "s": 2026, "cpi": 43.3},
            {"t": "paris", "s": 2026, "cpi": 50.0},
        ])

    def test_complete_source_replaces_fallback_and_preserves_published_rome(self):
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"generated": "2026-11-16T16:24:00Z", "readings": self.source_rows},
            2026,
        )
        by_key = {(tournament, season): value for tournament, season, value in rows}
        self.assertTrue(authoritative)
        self.assertEqual(106, len(rows))
        self.assertEqual(25.4, by_key[("rome", 2026)])
        self.assertEqual(50.0, by_key[("paris", 2026)])
        self.assertEqual(40.1, by_key[("atpfinals", 2025)])
        self.assertNotIn(("atpfinals", 2026), by_key)
        self.assertNotIn(("usopen", 2024), by_key)

    def test_incomplete_source_keeps_last_known_fallback(self):
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"readings": self.source_rows[:10]},
            2026,
        )
        self.assertFalse(authoritative)
        self.assertEqual(self.sorted_fallback, rows)

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
        self.assertEqual(self.sorted_fallback, rows)

    def test_invalid_source_row_falls_back_atomically(self):
        invalid = list(self.source_rows)
        invalid.append({"t": "broken", "s": 2026, "cpi": "not-a-number"})
        rows, authoritative = select_cpi_readings(
            self.fallback,
            {"readings": invalid},
            2026,
        )
        self.assertFalse(authoritative)
        self.assertEqual(self.sorted_fallback, rows)


if __name__ == "__main__":
    unittest.main()
