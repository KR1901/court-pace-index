import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from atp_event_periods import (
    EventPeriodError,
    audit_source_coverage,
    completed_before,
    load_event_periods,
    period_for_rows,
)


def record(**updates):
    value = {
        "season": 2026,
        "eventId": "2026-madrid",
        "tournamentId": "madrid",
        "sourceEventIds": ["2026-1536"],
        "startDate": "2026-04-22",
        "endDate": "2026-05-03",
        "timeZone": "Europe/Madrid",
        "sourceUrl": "https://www.atptour.com/en/scores/archive/madrid/1536/2026/results",
        "sourceKind": "results_archive",
        "dateStatus": "actual",
        "verifiedAt": "2026-11-27",
    }
    value.update(updates)
    return value


class OfficialEventPeriodRegistryTests(unittest.TestCase):
    def registry(self, records):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "periods.json"
            path.write_text(json.dumps({"schemaVersion": 1, "records": records}), encoding="utf-8")
            return load_event_periods(path)

    def test_local_event_period_boundaries_convert_to_utc(self):
        registry = self.registry([record()])
        madrid = registry["by_source_id"][(2026, "2026-1536")]
        self.assertEqual(datetime(2026, 4, 21, 22, 0, tzinfo=timezone.utc), madrid["_start_utc"])
        self.assertEqual(datetime(2026, 5, 3, 22, 0, tzinfo=timezone.utc), madrid["_end_utc_exclusive"])

    def test_full_event_must_end_before_target_begins_in_utc(self):
        registry = self.registry([
            record(),
            record(
                eventId="2026-rome", tournamentId="rome", sourceEventIds=["2026-1537"],
                startDate="2026-05-06", endDate="2026-05-17", timeZone="Europe/Rome",
                sourceUrl="https://www.atptour.com/en/scores/archive/rome/416/2026/results",
            ),
        ])
        madrid = registry["by_source_id"][(2026, "2026-1536")]
        rome = registry["by_source_id"][(2026, "2026-1537")]
        self.assertTrue(completed_before(madrid, rome))

        overlap_registry = self.registry([
            record(),
            record(
                eventId="2026-hong-kong", tournamentId="hongkong", sourceEventIds=["2026-7777"],
                startDate="2026-04-30", endDate="2026-05-06", timeZone="Asia/Hong_Kong",
                sourceUrl="https://www.atptour.com/en/scores/archive/hong-kong/7777/2026/results",
            ),
        ])
        self.assertFalse(completed_before(
            overlap_registry["by_source_id"][(2026, "2026-1536")],
            overlap_registry["by_source_id"][(2026, "2026-7777")],
        ))

    def test_scheduled_calendar_event_cannot_train_a_later_rating(self):
        registry = self.registry([
            record(sourceKind="calendar", dateStatus="scheduled"),
            record(
                eventId="2026-rome", tournamentId="rome", sourceEventIds=["2026-1537"],
                startDate="2026-05-06", endDate="2026-05-17", timeZone="Europe/Rome",
                sourceUrl="https://www.atptour.com/en/scores/archive/rome/416/2026/results",
            ),
        ])
        self.assertFalse(completed_before(
            registry["by_source_id"][(2026, "2026-1536")],
            registry["by_source_id"][(2026, "2026-1537")],
        ))

    def test_event_group_requires_every_source_id_to_resolve_to_one_edition(self):
        registry = self.registry([record()])
        rows = [{"tourney_id":"2026-1536"}, {"tourney_id":"2026-1536"}]
        self.assertEqual("2026-madrid", period_for_rows(rows, 2026, registry)["eventId"])
        self.assertIsNone(period_for_rows(rows + [{"tourney_id":"unmapped"}], 2026, registry))

    def test_source_coverage_counts_all_singles_even_without_stats(self):
        registry = self.registry([record()])
        rows = [
            {"tourney_id":"2026-1536", "tourney_level":"M", "winner_name":"A", "loser_name":"B"},
            {"tourney_id":"missing", "tourney_level":"250", "winner_name":"C", "loser_name":"D"},
            {"tourney_id":"doubles", "tourney_level":"M", "winner_name":"A/B", "loser_name":"C/D"},
        ]
        audit = audit_source_coverage(rows, 2026, registry)
        self.assertEqual(2, audit["identifiedSourceEvents"])
        self.assertEqual(1, audit["coveredSourceEvents"])
        self.assertFalse(audit["dateCoverageComplete"])
        self.assertEqual(["missing"], audit["missingSourceEventIds"])


    def test_multi_location_period_uses_conservative_worldwide_utc_envelope(self):
        registry = self.registry([record(
            timeZone="MULTI_LOCATION",
            startDate="2026-02-05",
            endDate="2026-02-08",
        )])
        period = registry["by_source_id"][(2026, "2026-1536")]
        self.assertEqual(datetime(2026, 2, 4, 10, 0, tzinfo=timezone.utc), period["_start_utc"])
        self.assertEqual(datetime(2026, 2, 9, 12, 0, tzinfo=timezone.utc), period["_end_utc_exclusive"])

    def test_multi_location_overlap_is_conservative_across_extreme_timezones(self):
        registry = self.registry([
            record(
                timeZone="MULTI_LOCATION",
                startDate="2026-02-05",
                endDate="2026-02-08",
            ),
            record(
                eventId="2026-target",
                tournamentId="rome",
                sourceEventIds=["2026-target"],
                startDate="2026-02-09",
                endDate="2026-02-15",
                timeZone="Europe/Rome",
                sourceUrl="https://www.atptour.com/en/scores/archive/rome/416/2026/results",
            ),
        ])
        source = registry["by_source_id"][(2026, "2026-1536")]
        target = registry["by_source_id"][(2026, "2026-target")]
        # The multi-location stage could run as late as 12:00 UTC on 9 February,
        # after the Rome target's earliest possible start that same day.
        self.assertFalse(completed_before(source, target))

    def test_davis_cup_is_outside_standard_atp_coverage_unless_explicitly_requested(self):
        rows = [
            {"tourney_id": "atp", "tourney_level": "250", "winner_name": "A", "loser_name": "B"},
            {"tourney_id": "dc", "tourney_level": "D", "winner_name": "C", "loser_name": "D"},
        ]
        registry = self.registry([record()])
        normal = audit_source_coverage(rows, 2026, registry)
        self.assertEqual(1, normal["identifiedSourceEvents"])
        self.assertTrue(normal["dateCoverageComplete"])
        strict_davis = audit_source_coverage(rows, 2026, registry, include_davis_cup=True)
        self.assertEqual(2, strict_davis["identifiedSourceEvents"])
        self.assertFalse(strict_davis["dateCoverageComplete"])
        self.assertEqual(["dc"], strict_davis["missingSourceEventIds"])

    def test_duplicate_source_ids_and_unknown_timezones_are_rejected(self):
        with self.assertRaises(EventPeriodError):
            self.registry([
                record(),
                record(eventId="other", sourceEventIds=["2026-1536"]),
            ])
        with self.assertRaises(EventPeriodError):
            self.registry([record(timeZone="Mars/Olympus")])

    def test_empty_registry_can_be_loaded_but_not_used_for_production(self):
        registry = self.registry([])
        self.assertEqual([], registry["records"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "periods.json"
            path.write_text('{"schemaVersion":1,"records":[]}', encoding="utf-8")
            with self.assertRaises(EventPeriodError):
                load_event_periods(path, require_non_empty=True)


if __name__ == "__main__":
    unittest.main()
