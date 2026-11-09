import unittest

from fetch_reference_data import parse_cpi_page, parse_ta_year, tournament_lookup


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
