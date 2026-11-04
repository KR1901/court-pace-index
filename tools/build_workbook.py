#!/usr/bin/env python3
"""Builds the dataset workbook published with each release.

The sheets carry the data; the Summary sheet is live formulas over them, so a reader can
add rows and have the season averages follow.
"""
import json, os
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

SRC = "app/src/main/assets/pace_dataset.json"
OUT = os.path.join(os.environ.get("OUT_DIR", "/scratch/output"), "court-pace-dataset-2.1.1.xlsx")

data = json.load(open(SRC))
tournaments = data["tournaments"]
readings = data["readings"]
meta = data["meta"]

HEADER_FONT = Font(name="Arial", bold=True, size=10, color="FFFFFF")
HEADER_FILL = PatternFill("solid", fgColor="1F2937")
BODY_FONT = Font(name="Arial", size=10)
TITLE_FONT = Font(name="Arial", bold=True, size=14)
NOTE_FONT = Font(name="Arial", size=9, italic=True, color="555555")


def style_header(ws, columns):
    for i, name in enumerate(columns, start=1):
        cell = ws.cell(row=1, column=i, value=name)
        cell.font = HEADER_FONT
        cell.fill = HEADER_FILL
        cell.alignment = Alignment(vertical="center")
        ws.column_dimensions[get_column_letter(i)].width = max(12, min(34, len(name) + 6))
    ws.freeze_panes = "A2"


wb = Workbook()

# ---- Read me ------------------------------------------------------------------------
ws = wb.active
ws.title = "Read me"
ws["A1"] = "Court Pace — dataset"
ws["A1"].font = TITLE_FONT
lines = [
    ("", ""),
    ("Generated", meta["generated"]),
    ("Season", str(meta["season"])),
    ("Events", str(len(tournaments))),
    ("Readings", str(len(readings))),
    ("", ""),
    ("Court Pace Index", "Physical bounce reading from Hawk-Eye on the show courts, 2012–2026."),
    ("  formula", "CPI = 100(1 - mu) + 150(0.81 - e)"),
    ("  source", "courtspeed.com database, compiled from ATP / Tennis TV broadcast data"),
    ("", ""),
    ("Surface speed", "Ace rate adjusted for the server and returner in each match; 1.00 = tour average."),
    ("  published", "Tennis Abstract surface speed ratings, used for the 2025–26 readings"),
    ("  computed", "Recomputed by this project from Jeff Sackmann's ATP archive, 2012–2025"),
    ("  validation", "r = 0.925 against the published 2026 season, mean |diff| 0.097"),
    ("", ""),
    ("Licence", "Code MIT. The computed series derives from CC BY-NC-SA 4.0 data:"),
    ("", "attribution required, non-commercial use only, share-alike on derived data."),
    ("", ""),
    ("Columns", "CPI and Speed sheets carry one row per event per season."),
    ("", "The Source column says which series a speed figure came from."),
    ("", "Summary is live formulas over those two sheets — add rows and it follows."),
]
for i, (a, b) in enumerate(lines, start=2):
    ws.cell(row=i, column=1, value=a).font = BODY_FONT if a not in ("Court Pace Index", "Surface speed") else Font(name="Arial", bold=True, size=10)
    c = ws.cell(row=i, column=2, value=b)
    c.font = NOTE_FONT if a.startswith("  ") else BODY_FONT
ws.column_dimensions["A"].width = 18
ws.column_dimensions["B"].width = 86

# ---- Tournaments --------------------------------------------------------------------
ws = wb.create_sheet("Tournaments")
cols = ["ID", "Name", "City", "Country", "Surface", "Indoor", "Level", "Altitude (m)"]
style_header(ws, cols)
for r, t in enumerate(sorted(tournaments, key=lambda x: x["name"]), start=2):
    values = [t["id"], t["name"], t["city"], t["country"], t["surface"].title(),
              "Yes" if t["indoor"] else "No", t["level"], t.get("altitudeM")]
    for c, v in enumerate(values, start=1):
        cell = ws.cell(row=r, column=c, value=v)
        cell.font = BODY_FONT

# ---- CPI ----------------------------------------------------------------------------
ws = wb.create_sheet("CPI")
style_header(ws, ["Event ID", "Season", "CPI"])
row = 2
for reading in sorted((r for r in readings if r.get("cpi") is not None), key=lambda r: (r["s"], r["t"])):
    ws.cell(row=row, column=1, value=reading["t"]).font = BODY_FONT
    ws.cell(row=row, column=2, value=reading["s"]).font = BODY_FONT
    ws.cell(row=row, column=3, value=reading["cpi"]).font = BODY_FONT
    row += 1
cpi_last = row - 1

# ---- Speed --------------------------------------------------------------------------
ws = wb.create_sheet("Speed")
style_header(ws, ["Event ID", "Season", "Speed", "Ace %", "Source"])
row = 2
for reading in sorted((r for r in readings if r.get("speed") is not None), key=lambda r: (r["s"], r["t"])):
    ws.cell(row=row, column=1, value=reading["t"]).font = BODY_FONT
    ws.cell(row=row, column=2, value=reading["s"]).font = BODY_FONT
    ws.cell(row=row, column=3, value=reading["speed"]).font = BODY_FONT
    ws.cell(row=row, column=4, value=reading.get("ace")).font = BODY_FONT
    ws.cell(row=row, column=5, value=reading["src"]).font = BODY_FONT
    row += 1
speed_last = row - 1

# ---- Summary (live formulas) --------------------------------------------------------
ws = wb.create_sheet("Summary")
style_header(ws, ["Season", "CPI readings", "Mean CPI", "Speed readings", "Mean speed", "Mean ace %"])
seasons = sorted({r["s"] for r in readings})
for i, season in enumerate(seasons, start=2):
    ws.cell(row=i, column=1, value=season).font = BODY_FONT
    formulas = [
        f'=COUNTIF(CPI!$B$2:$B${cpi_last},$A{i})',
        f'=IFERROR(AVERAGEIF(CPI!$B$2:$B${cpi_last},$A{i},CPI!$C$2:$C${cpi_last}),"")',
        f'=COUNTIF(Speed!$B$2:$B${speed_last},$A{i})',
        f'=IFERROR(AVERAGEIF(Speed!$B$2:$B${speed_last},$A{i},Speed!$C$2:$C${speed_last}),"")',
        f'=IFERROR(AVERAGEIF(Speed!$B$2:$B${speed_last},$A{i},Speed!$D$2:$D${speed_last}),"")',
    ]
    for c, f in enumerate(formulas, start=2):
        cell = ws.cell(row=i, column=c, value=f)
        cell.font = BODY_FONT
        cell.number_format = "0.00" if c in (3, 5, 6) else "0"

# A note where the reader will see it, per the skill's rule on documenting assumptions.
note_row = len(seasons) + 3
ws.cell(row=note_row, column=1, value="Means are unweighted across events, matching how the app reports them.").font = NOTE_FONT
ws.cell(row=note_row + 1, column=1, value="CPI covers only the courts with Hawk-Eye installed; the speed column covers the whole tour.").font = NOTE_FONT

wb.save(OUT)
print("wrote", OUT, "| cpi rows", cpi_last - 1, "| speed rows", speed_last - 1, "| seasons", len(seasons))
