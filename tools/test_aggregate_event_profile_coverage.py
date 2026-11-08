import json
import tempfile
import unittest
from pathlib import Path

import aggregate_event_profile_coverage as aggregate


class AggregateEventProfileCoverageTests(unittest.TestCase):
    def write_summary(self, root, year, *, sides=4, rateable=4, priors=1):
        folder = Path(root) / str(year)
        folder.mkdir(parents=True)
        summary = {
            "season": year,
            "source": "test source",
            "event_count": 1,
            "raw_event_rows": 2,
            "eligible_matches": 2,
            "eligible_server_sides": sides,
            "rateable_server_sides": rateable,
            "unrateable_server_sides": sides - rateable,
            "events_with_unrateable_sides": int(sides != rateable),
            "tour_prior_sides": priors,
            "events_with_tour_prior_sides": int(priors > 0),
            "fallback_diagnostics": {
                "profile_exists_without_rate_occurrences": 1,
                "ambiguous_name_match_occurrences": 1,
                "no_player_profile_in_history_occurrences": 1,
                "server_role_fallback_occurrences": 1,
                "returner_role_fallback_occurrences": 1,
                "distinct_fallback_player_ids": 2,
                "distinct_fallback_player_role_keys": 2,
            },
            "events": [{
                "event_id": f"event-{year}",
                "event_names": "Test Event",
                "eligible_matches": 2,
                "eligible_server_sides": sides,
                "rateable_server_sides": rateable,
                "unrateable_server_sides": sides - rateable,
                "tour_prior_sides": priors,
            }],
            "source_metadata": {"sha256": "test-hash"},
        }
        (folder / "summary.json").write_text(
            json.dumps(summary), encoding="utf-8"
        )

    def test_aggregates_complete_multi_season_range_and_writes_reports(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "input"
            self.write_summary(root, 2025, sides=4, rateable=4, priors=1)
            self.write_summary(root, 2026, sides=6, rateable=5, priors=2)

            summaries = aggregate.load_year_summaries(root, 2025, 2026)
            report = aggregate.aggregate_summaries(summaries, 2025, 2026)
            output = Path(temp) / "output"
            aggregate.write_report(report, output)

            self.assertEqual(2, report["season_count"])
            self.assertEqual(2, report["totals"]["event_count"])
            self.assertEqual(10, report["totals"]["eligible_server_sides"])
            self.assertEqual(9, report["totals"]["rateable_server_sides"])
            self.assertEqual(3, report["totals"]["tour_prior_sides"])
            self.assertEqual(2, report["fallback_diagnostics"]["ambiguous_name_match_occurrences"])
            self.assertAlmostEqual(90.0, report["totals"]["rateable_pct"])
            self.assertEqual(2, len(report["events"]))
            self.assertTrue((output / "summary.json").is_file())
            self.assertTrue((output / "coverage-by-season.csv").is_file())
            self.assertTrue((output / "coverage-by-event.csv").is_file())
            self.assertTrue((output / "README.md").is_file())
            saved = json.loads((output / "summary.json").read_text(encoding="utf-8"))
            self.assertFalse(saved["player_names_and_ids_published"])

    def test_missing_year_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            self.write_summary(temp, 2025)
            with self.assertRaises(FileNotFoundError):
                aggregate.load_year_summaries(temp, 2025, 2026)

    def test_event_count_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            self.write_summary(temp, 2025)
            path = Path(temp) / "2025" / "summary.json"
            summary = json.loads(path.read_text(encoding="utf-8"))
            summary["event_count"] = 2
            path.write_text(json.dumps(summary), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "event_count"):
                aggregate.load_year_summaries(temp, 2025, 2025)


if __name__ == "__main__":
    unittest.main()
