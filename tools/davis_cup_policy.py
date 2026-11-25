"""Publication policy for source-validated Davis Cup Surface Speed calculations."""

# Historical archive coverage has been validated for these editions. The current-year
# exception is source-gated: TML must publish actual Davis Cup Finals match statistics
# with both players' ace counts and service-point denominators.
VALIDATED_DAVIS_CUP_ARCHIVE_YEARS = frozenset(range(2016, 2025))
CURRENT_DAVIS_CUP_SEASON = 2026
TML_CURRENT_SOURCE = "tennismylife atp match database"


def is_supported_davis_cup_calculation_year(
    tournament_id: str,
    season: int | str,
    *,
    source: str | None = None,
    davis_cup_finals_matches_with_stats: int = 0,
) -> bool:
    """Return whether a Davis Cup season has a source-validated match-stat population."""
    if str(tournament_id).casefold() != "daviscup":
        return True
    try:
        year = int(season)
    except (TypeError, ValueError):
        return False
    if year in VALIDATED_DAVIS_CUP_ARCHIVE_YEARS:
        return True
    source_label = str(source or "").casefold()
    return (
        year == CURRENT_DAVIS_CUP_SEASON
        and TML_CURRENT_SOURCE in source_label
        and int(davis_cup_finals_matches_with_stats or 0) > 0
    )
