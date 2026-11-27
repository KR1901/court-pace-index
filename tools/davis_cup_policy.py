"""Publication policy for source-validated Davis Cup Surface Speed calculations."""

# Historical overall Davis Cup years have been validated against the licensed archive.
VALIDATED_HISTORICAL_DAVIS_CUP_YEARS = frozenset(range(2016, 2025))

# Both current-season phases require the annual TML ATP CSV and ongoing supplement.
TML_DAVIS_CUP_SOURCE = "TennisMyLife ATP Match Database + ongoing_tourneys.csv"


def is_supported_davis_cup_calculation_year(
    tournament_id: str,
    season: int | str,
    source: str | None = None,
) -> bool:
    """Return whether a tournament-phase/season may publish an archive CALC reading."""
    event_id = str(tournament_id).casefold()
    if event_id not in {"daviscup", "daviscupfinal8"}:
        return True
    try:
        year = int(season)
    except (TypeError, ValueError):
        return False

    if event_id == "daviscup":
        if year in VALIDATED_HISTORICAL_DAVIS_CUP_YEARS:
            return True
        return year == 2026 and source == TML_DAVIS_CUP_SOURCE

    # The separately named Final 8 phase is source-identified in the 2026 TML feed.
    # Do not manufacture historical Final 8 rows from legacy aggregate Davis Cup labels.
    return year == 2026 and source == TML_DAVIS_CUP_SOURCE
