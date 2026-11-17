# First-party open-data contribution format

**Status: design only.** This describes a first-party contribution format for records that contributors have independently collected and have the right to publish. It is not permission to scrape ATP, WTA, tournament, broadcaster or other restricted websites.

## Publication licence

Every accepted row must carry one of these source-data licences:

- `CC0-1.0` — preferred for factual community contributions; or
- `CC-BY-4.0` — accepted when the contributor wants attribution.

Do not accept NC, ND, research-only, display-only, or API-subscription terms as an open-data grant. Code and collector software may have a separate MIT licence; that does not relicense contributed records.

Each contributor must confirm that:
1. the values were independently observed, measured, or sourced from a feed that explicitly allows this form of open redistribution;
2. they are entitled to grant the selected licence;
3. they have not copied values from a restricted ATP/TML/TA/Court Speed page or a non-redistributable API;
4. corrections preserve the source and audit trail.

Do not publish contributor email addresses, account identifiers, private notes or personal details. Use a random contributor key if attribution for a CC BY contribution is required.

## Match record: `matches.jsonl`

One JSON object per match. Use UTF-8 JSON Lines, one object per line. An absent measurement is `null`, never zero.

```json
{
  "schemaVersion": 1,
  "recordType": "match",
  "matchId": "community:2026:example-event:match-001",
  "event": {
    "eventId": "community:example-event:2026",
    "name": "Example Open",
    "season": 2026,
    "level": "ATP250",
    "surface": "hard",
    "environment": "indoor",
    "countryCode": "IND"
  },
  "date": {
    "playedAt": "2026-01-15T10:30:00Z",
    "precision": "exact"
  },
  "round": "R16",
  "status": "completed",
  "score": "6-4 6-3",
  "players": [
    {
      "playerId": "wikidata:Q000000",
      "name": "Player One",
      "countryCode": "IND"
    },
    {
      "playerId": "community-player:000002",
      "name": "Player Two",
      "countryCode": "AUS"
    }
  ],
  "winnerPlayerId": "wikidata:Q000000",
  "stats": {
    "status": "complete",
    "playerOne": {
      "aces": 8,
      "doubleFaults": 2,
      "servicePoints": 52,
      "firstServeIn": 35,
      "firstServePointsWon": 28,
      "secondServePointsWon": 10,
      "serviceGames": 9,
      "breakPointsSaved": 3,
      "breakPointsFaced": 4
    },
    "playerTwo": {
      "aces": 4,
      "doubleFaults": 3,
      "servicePoints": 61,
      "firstServeIn": 38,
      "firstServePointsWon": 25,
      "secondServePointsWon": 11,
      "serviceGames": 9,
      "breakPointsSaved": 2,
      "breakPointsFaced": 6
    }
  },
  "provenance": {
    "sourceType": "independent_observation",
    "sourceUrl": null,
    "observedAt": "2026-01-15T13:00:00Z",
    "contributorKey": "contributor:random-key",
    "licence": "CC0-1.0",
    "attribution": null,
    "notes": "Independently recorded from an authorised match-stat display."
  },
  "validation": {
    "status": "reviewed",
    "parserVersion": "manual-v1",
    "checksum": "sha256:replace-with-record-hash"
  }
}
```

### Match enums and validation

- `status`: `in_progress`, `completed`, `retired`, `walkover`, `abandoned`, `cancelled`.
- `stats.status`: `complete`, `partial`, `unavailable`.
- `surface`: `hard`, `clay`, `grass`, `carpet`, `other`, `unknown`.
- `environment`: `indoor`, `outdoor`, `unknown`.
- `date.precision`: `exact`, `day`, `week`, `unknown`. Only an exact, directly evidenced match date may be used for timestamp-level live ordering.
- A walkover or other no-play record must not contain invented played statistics.
- A retirement may be included in Player Stats as a result; it may enter CALC only when the relevant server-side statistics are valid.
- Check integer fields for non-negative values and relationships: first-serve-in cannot exceed service points; break points saved cannot exceed faced; the number of breaks conceded cannot exceed service games.
- Do not derive service-game totals from score text when the actual field is missing.
- Player IDs must be stable and namespaced. A Wikidata QID is allowed only after verifying that it identifies the same player. Do not use fuzzy name matching as a join key.
- Match identity must be unique and immutable. Corrections create a new revision that references the previous record ID.

## Player fact: `player_facts.jsonl`

One record per sourced fact, rather than one mutable profile blob:

```json
{
  "schemaVersion": 1,
  "recordType": "player_fact",
  "factId": "wikidata:Q000000:P2048:revision-123",
  "playerId": "wikidata:Q000000",
  "property": "heightCm",
  "value": 188,
  "provenance": {
    "sourceType": "wikidata",
    "sourceUrl": "https://www.wikidata.org/wiki/Q000000",
    "sourceRevision": "123",
    "retrievedAt": "2026-11-17T10:00:00Z",
    "licence": "CC0-1.0"
  },
  "validation": {
    "status": "unreviewed"
  }
}
```

Height is an integer in centimetres. Keep conflicting or missing facts separate until reviewed. The app may display both metric and imperial conversions from the same canonical value; do not store two independently rounded heights.

## Court measurement: `court_measurements.jsonl`

One record per measured court/test session. A tournament-level CPI value must not be inferred from a surface brand or an unrelated laboratory sample.

```json
{
  "schemaVersion": 1,
  "recordType": "court_measurement",
  "measurementId": "community:example-event:2026:court-1:test-001",
  "eventId": "community:example-event:2026",
  "court": {
    "courtId": "community:example-event:2026:court-1",
    "name": "Court 1",
    "surface": "hard",
    "indoor": true
  },
  "measuredAt": "2026-01-10T08:00:00Z",
  "method": {
    "name": "documented ball-surface test",
    "standard": null,
    "equipment": "Describe the apparatus and calibration",
    "protocolVersion": "1"
  },
  "conditions": {
    "airTemperatureC": 22.0,
    "surfaceTemperatureC": 21.0,
    "relativeHumidityPercent": 45
  },
  "measurements": {
    "coefficientOfFriction": 0.62,
    "coefficientOfRestitution": 0.80,
    "cpi": 39.5
  },
  "provenance": {
    "sourceType": "independent_measurement",
    "contributorKey": "contributor:random-key",
    "licence": "CC-BY-4.0",
    "attribution": "Court Pace community measurement"
  },
  "validation": {
    "status": "pending_review"
  }
}
```

Keep the raw observation counts, repeat measurements, apparatus/calibration details and protocol evidence in the submitted record or referenced open attachment. Store calculated CPI alongside—not instead of—the raw friction/restitution measurements. If either input is absent, CPI remains `null`.

## Release and review rules

1. Reject records without a source/contributor and explicit CC0 or CC BY 4.0 grant.
2. Reject records whose stated source conflicts with its licence or terms.
3. Validate identity, dates, match lifecycle and field relationships before merging.
4. Preserve raw submitted values, revision history, reviewer decision and hash.
5. Publish the licence and attribution adjacent to every dataset release; do not put only code under MIT and imply it covers data.
6. Run the full CALC and Player Stats coverage audit before generating assets. Incomplete coverage must be shown as incomplete.
7. Never make a current APK or public JSON claim “fully open data” while it contains legacy NC, restricted-service or unlicensed source-derived values.

This format is a starting contract for independent community contributions. It does not itself create data, verify contributors' rights, or make the current production dataset open.
