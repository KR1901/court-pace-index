#!/usr/bin/env python3
"""Probes the free, keyless Sofascore tennis API for live match statistics."""
import json, urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Safari/537.36")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.read().decode("utf-8", "replace")


def main():
    for url in (
        "https://api.sofascore.com/api/v1/sport/tennis/events/live",
        "https://www.sofascore.com/api/v1/sport/tennis/events/live",
    ):
        try:
            status, body = get(url)
            print(f"{url} -> {status}, {len(body)} bytes")
            data = json.loads(body)
            events = data.get("events", [])
            print(f"  live events: {len(events)}")
            for e in events[:12]:
                t = e.get("tournament", {})
                ut = t.get("uniqueTournament", {})
                print(f"   - {ut.get('name')} | {t.get('name')} | eventId={e.get('id')} "
                      f"| status={e.get('status', {}).get('type')}")
            return
        except Exception as exc:
            print(f"{url} -> FAILED: {exc}")


if __name__ == "__main__":
    main()
