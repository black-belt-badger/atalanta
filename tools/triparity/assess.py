#!/usr/bin/env python3
"""Audit-only comparison of native decoder smoke markers to canonical fixtures.

This is not a full normalized public-API conformance test. It deliberately
distinguishes known differences from unexpected regressions and build failures.
"""
import json
import re
import sys
from pathlib import Path

MARKER = re.compile(r"PORTABLE-SMOKE (PASS|MISMATCH) ([a-z][a-z0-9_.]+)")


def inventory(manifest_path: Path) -> list[str]:
    manifest = json.loads(manifest_path.read_text())
    assert manifest["revision"] == "1.0.0"
    ids = [case["id"] for case in manifest["cases"]]
    assert len(ids) == 26 and len(set(ids)) == 26, "Portable manifest has changed"
    return ids


def assess(language: str, logfile: Path, exit_code: int,
           expected_ids: list[str], known: dict[str, str]) -> bool:
    log = logfile.read_text(errors="replace")
    results: dict[str, str] = {}
    duplicate: set[str] = set()
    for result, case_id in MARKER.findall(log):
        if case_id in results:
            duplicate.add(case_id)
        results[case_id] = result

    missing = sorted(set(expected_ids) - set(results))
    unexpected = sorted(set(results) - set(expected_ids))
    gaps = {case_id for case_id, verdict in results.items() if verdict == "MISMATCH"}
    newly_detected = sorted(gaps - set(known))
    fixed = sorted(set(known) - gaps)
    malformed = bool(duplicate or missing or unexpected)
    status_error = (exit_code != 0 and not gaps) or (exit_code == 0 and bool(gaps))
    success = not (malformed or status_error or newly_detected)

    print(f"{language}: {len(results) - len(gaps)}/{len(expected_ids)} provider "
          f"decode/status smoke cases matched; {len(gaps)} known mismatches")
    for case_id in sorted(gaps):
        print(f"  MISMATCH {case_id}: {known.get(case_id, 'UNEXPECTED GAP')}")
    for case_id in fixed:
        print(f"  IMPROVED {case_id}: previously known mismatch now passes")
    for name, values in (("missing markers", missing), ("unknown markers", unexpected),
                         ("duplicate markers", sorted(duplicate))):
        if values:
            print(f"  ERROR {name}: {values}")
    if status_error:
        print(f"  ERROR test process returned {exit_code} inconsistently with markers")
    if newly_detected:
        print(f"  ERROR new parity mismatches: {newly_detected}")
    return success


def main() -> int:
    if len(sys.argv) != 7:
        print("Usage: assess.py MANIFEST GAPS KIRKE_LOG KIRKE_STATUS MEDEA_LOG MEDEA_STATUS",
              file=sys.stderr)
        return 2

    manifest_path = Path(sys.argv[1])
    gaps = json.loads(Path(sys.argv[2]).read_text())
    expected_ids = inventory(manifest_path)
    assert gaps["fixtureRevision"] == "1.0.0"

    passed = [
        assess("Kirke", Path(sys.argv[3]), int(sys.argv[4]),
               expected_ids, gaps["known"]["kirke"]),
        assess("Medea", Path(sys.argv[5]), int(sys.argv[6]),
               expected_ids, gaps["known"]["medea"]),
    ]
    print("Smoke scope: 26 provider response shapes and HTTP statuses; "
          "NOT normalized field assertions, wire query parity, or live API coverage.")
    if all(passed):
        print("AUDIT BASELINE VERIFIED: no unexpected decoder gaps")
        return 0
    print("AUDIT BASELINE REGRESSION: new failures or inconclusive test execution")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
