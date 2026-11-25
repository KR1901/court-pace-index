"""Publication policy for source-validated Davis Cup Surface Speed calculations."""

# Historical years have been validated against the licensed historical archive.
VALIDATED_HISTORICAL_DAVIS_CUP_YEARS = frozenset(range(2016, 2025))

# The 2026 edition is permitted only when both the annual TML archive and the
# current-event supplement were retrieved, schema-checked and combined in one run.
TML_DAVIS_CUP_SOURCE = "TennisMyLife ATP Match Database + ongoing_tourneys.csv"


def is_supported_davis_cup_calculation_year(
    tournament_id: str,
    season: int | str,
    source: str | None = None,
) -> bool:
    """Return whether a tournament-season may publish an archive CALC reading."""
    if str(tournament_id).casefold() != "daviscup":
        return True
    try:
        year = int(season)
    except (TypeError, ValueError):
        return False
    if year in VALIDATED_HISTORICAL_DAVIS_CUP_YEARS:
        return True
    return year == 2026 and source == TML_DAVIS_CUP_SOURCE
