#!/usr/bin/env python3
"""Diagnostic-only ATP page probe through r.jina.ai (legacy filename: no SofaScore use).

Does not fetch Infosys match statistics, update data/live.json, or calculate a rating.
It is retained only as a reachability diagnostic for the scheduled dataset publisher.
"""
import re, urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Safari/537.36")

TARGET = "https://www.atptour.com/en/scores/current/paris/352/results"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.read().decode("utf-8", "replace")


def main():
    for reader in (f"https://r.jina.ai/{TARGET}",):
        try:
            status, body = get(reader)
            print(f"{reader} -> {status}, {len(body)} bytes")
            ids = sorted(set(re.findall(r"stats-centre/(?:live|archive)/\d+/\d+/([a-zA-Z]+\d+)", body)))
            print(f"  stats-centre match ids found: {len(ids)} {ids[:12]}")
            print(f"  contains 'wins the match': {'wins the match' in body}")
            print(f"  contains 'Aces': {'Aces' in body}")
            print("  head:", body[:200].replace("\n", " "))
        except Exception as exc:
            print(f"{reader} -> FAILED: {exc}")


if __name__ == "__main__":
    main()
