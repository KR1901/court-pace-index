"""Publication policy for source-validated Davis Cup Surface Speed calculations."""

# The archive has validated Davis Cup serve-stat coverage only in these editions.
# Do not infer Surface Speed values outside this range from sparse or unverified rows.
VALIDATED_DAVIS_CUP_CALC_YEARS = frozenset(range(2016, 2025))


def is_supported_davis_cup_calculation_year(tournament_id: str, season: int | str) -> bool:
    """Return whether a tournament-season may publish an archive CALC reading."""
    if str(tournament_id).casefold() != "daviscup":
        return True
    try:
        year = int(season)
    except (TypeError, ValueError):
        return False
    return year in VALIDATED_DAVIS_CUP_CALC_YEARS
