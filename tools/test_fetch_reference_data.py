import unittest

from fetch_reference_data import (
    parse_cpi_page,
    parse_ta_year,
    ta_years_to_refresh,
    tournament_lookup,
)


class ReferenceRefreshTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.aliases, cls.exact = tournament_lookup()

    def test_calendar_year_page_not_start_date_determines_season(self):
        html = """
        <html><body><h1>2025 ATP Surface Speed Ratings</h1>
        <table><tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Surface Speed</th></tr>
        <tr><td>30-Dec-2024</td><td>Hong Kong</td><td>Hard</td><td>9.6%</td><td>1.31</td></tr>
        <tr><td>19-Sep-2025</td><td>Laver Cup</td><td>Hard</td><td>5.8%</td><td>0.55</td></tr>
        </table></body></html>
        """
        rows, unmapped = parse_ta_year(html, 2025, self.aliases, self.exact)
        by_id = {row["t"]: row for row in rows}
        self.assertEqual(2025, by_id["hongkong"]["s"])
        self.assertEqual("2024-12-30", by_id["hongkong"]["date"])
        self.assertEqual(2025, by_id["lavercup"]["s"])
        self.assertEqual(0.55, by_id["lavercup"]["speed"])
        self.assertEqual([], unmapped)

    def test_laver_cup_is_stored_as_two_distinct_seasons(self):
        html_2025 = """
        <table><tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Speed</th></tr>
        <tr><td>19-Sep-2025</td><td>Laver Cup</td><td>Hard</td><td>5.8%</td><td>0.55</td></tr></table>
        """
        html_2026 = """
        <table><tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Speed</th></tr>
        <tr><td>25-Sep-2026</td><td>Laver Cup</td><td>Hard</td><td>4.4%</td><td>0.44</td></tr></table>
        """
        old, _ = parse_ta_year(html_2025, 2025, self.aliases, self.exact)
        new, _ = parse_ta_year(html_2026, 2026, self.aliases, self.exact)
        self.assertEqual((2025, 0.55), (old[0]["s"], old[0]["speed"]))
        self.assertEqual((2026, 0.44), (new[0]["s"], new[0]["speed"]))

    def test_missing_historical_ta_years_are_backfilled_once(self):
        existing = {"2025": [{"t": "brisbane"}], "2026": [{"t": "brisbane"}]}
        years = ta_years_to_refresh(2012, 2026, existing)
        self.assertEqual(list(range(2012, 2027)), years)

    def test_fully_backfilled_ta_snapshot_only_refreshes_recent_two_years(self):
        existing = {str(year): [{"t": "brisbane"}] for year in range(1991, 2027)}
        self.assertEqual([2025, 2026], ta_years_to_refresh(1991, 2026, existing))

    def test_empty_historical_year_is_retried(self):
        existing = {
            **{
                str(year): [{"t": "brisbane"}]
                for year in range(2012, 2027)
                if year != 2012
            },
            "2012": [],
        }
        self.assertEqual([2012, 2025, 2026], ta_years_to_refresh(2012, 2026, existing))

    def test_request_start_year_limits_the_backfill_window(self):
        existing = {"2025": [{"t": "brisbane"}], "2026": [{"t": "brisbane"}]}
        self.assertEqual(
            list(range(2020, 2027)),
            ta_years_to_refresh(2020, 2026, existing),
        )

    def test_historical_ta_event_aliases_are_resolved(self):
        html = """
        <table><tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Speed</th></tr>
        <tr><td>10-Feb-2025</td><td>Marseille</td><td>Hard</td><td>12.3%</td><td>1.05</td></tr>
        <tr><td>13-Oct-2025</td><td>Stockholm</td><td>Hard</td><td>9.1%</td><td>0.90</td></tr>
        <tr><td>17-Dec-2025</td><td>Next Gen Finals</td><td>Hard</td><td>10.2%</td><td>0.81</td></tr>
        </table>
        """
        rows, unmapped = parse_ta_year(html, 2025, self.aliases, self.exact)
        self.assertEqual(
            {"marseille", "stockholm", "nextgen"},
            {row["t"] for row in rows},
        )
        self.assertEqual([], unmapped)

    def test_masters_cup_is_mapped_to_the_atp_finals_tournament(self):
        html = """
        <table><tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Speed</th></tr>
        <tr><td>16-Nov-2008</td><td>Masters Cup</td><td>Hard</td><td>7.2%</td><td>1.02</td></tr>
        </table>
        """
        rows, unmapped = parse_ta_year(html, 2008, self.aliases, self.exact)
        self.assertEqual("atpfinals", rows[0]["t"])
        self.assertEqual(2008, rows[0]["s"])
        self.assertEqual(1.02, rows[0]["speed"])
        self.assertEqual([], unmapped)

    def test_newly_resolvable_historical_labels_trigger_only_their_seasons(self):
        existing = {str(year): [{"t": "brisbane"}] for year in range(1991, 2027)}
        unmapped = {
            "2000": ["Masters Cup"],
            "2008": ["Masters Cup"],
            "2012": ["Non-catalogue event"],
        }
        years = ta_years_to_refresh(
            1991, 2026, existing, unmapped, self.aliases, self.exact
        )
        self.assertEqual([2000, 2008, 2025, 2026], years)

    def test_new_aliases_are_not_refetched_after_their_labels_are_mapped(self):
        existing = {str(year): [{"t": "brisbane"}] for year in range(1991, 2027)}
        unmapped = {"2000": ["Other unmapped event"], "2008": ["Other unmapped event"]}
        years = ta_years_to_refresh(
            1991, 2026, existing, unmapped, self.aliases, self.exact
        )
        self.assertEqual([2025, 2026], years)

    def test_courtspeed_year_columns_are_parsed(self):
        html = """
        <table>
          <tr><th>Tournament</th><th>'19</th><th>'20</th><th>'21</th><th>'22</th><th>'23</th><th>'24</th><th>'25</th><th>'26</th></tr>
          <tr><td>◆Australian Open Aus Open Hard</td><td>–</td><td>–</td><td>50</td><td>–</td><td>–</td><td>–</td><td>–</td><td>50</td></tr>
          <tr><td>Indian Wells Hard</td><td>32.1</td><td>COVID</td><td>32</td><td>–</td><td>35.4</td><td>36.9</td><td>30.9</td><td>39.3</td></tr>
          <tr><td>Paris Hard indoor</td><td>40.6</td><td>–</td><td>37.1</td><td>37.1</td><td>40.4</td><td>46.6</td><td>35.1</td><td>50</td></tr>
        </table>
        """
        rows = parse_cpi_page(html, self.aliases, self.exact)
        by_key = {(row["t"], row["s"]): row["cpi"] for row in rows}
        self.assertEqual(50.0, by_key[("ausopen", 2026)])
        self.assertEqual(30.9, by_key[("indianwells", 2025)])
        self.assertEqual(50.0, by_key[("paris", 2026)])


if __name__ == "__main__":
    unittest.main()
