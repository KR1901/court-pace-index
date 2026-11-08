import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools" / "build_dataset.py"


def read_cpi_rows():
    tree = ast.parse(BUILDER.read_text(encoding="utf-8"))
    assignment = next(
        node for node in tree.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "CPI"
                for target in node.targets)
    )
    return ast.literal_eval(assignment.value)


class PhysicalCpiSnapshotTests(unittest.TestCase):
    def test_snapshot_has_unique_event_season_keys(self):
        rows = read_cpi_rows()
        keys = [(row[0], row[1]) for row in rows]
        self.assertEqual(107, len(rows))
        self.assertEqual(len(keys), len(set(keys)))

    def test_latest_courtspeed_revisions_are_retained(self):
        rows = {
            (event, year): value
            for event, year, value in read_cpi_rows()
        }
        expected = {
            ("canada", 2019): 43.4,
            ("canada", 2025): 44.7,
            ("canada", 2026): 36.5,
            ("cincinnati", 2025): 43.7,
            ("cincinnati", 2026): 40.4,
            ("shanghai", 2018): 39.9,
            ("paris", 2024): 46.6,
            ("paris", 2026): 50.0,
        }
        for key, value in expected.items():
            with self.subTest(key=key):
                self.assertEqual(value, rows[key])

    def test_clay_values_were_not_changed_in_the_2026_source_reconciliation(self):
        rows = {
            (event, year): value
            for event, year, value in read_cpi_rows()
        }
        clay_keys = {
            key for key in rows
            if key[0] in {"montecarlo", "madrid", "rome", "rolandgarros"}
        }
        # No clay value is part of the hard-court source revision set.
        self.assertGreaterEqual(len(clay_keys), 24)
        self.assertEqual(27.1, rows[("montecarlo", 2026)])
        self.assertEqual(29.8, rows[("madrid", 2026)])
        self.assertEqual(25.4, rows[("rome", 2026)])


if __name__ == "__main__":
    unittest.main()
