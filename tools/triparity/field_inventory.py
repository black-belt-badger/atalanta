#!/usr/bin/env python3
"""Generate observed provider-wire field inventory for triparity review.

Only canonical synthetic fixture fields are shown. This is NOT a claim that
all published Alpaca schema fields are exhaustively covered by 26 cases.
"""
import json
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "contracts/alpaca-trading/v1"
OUT = ROOT / "docs/TRIPARITY_FIELD_INVENTORY.md"


def wire_type(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, str):
        return "string"
    if isinstance(value, (int, Decimal)):
        return "number"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return "other"


def records_by_section():
    cases = json.loads((PACK / "manifest.json").read_text())
    collected = defaultdict(list)
    for case in cases["cases"]:
        if case["response"]["status"] != 200:
            continue
        body = json.loads((PACK / case["response"]["body"]).read_text(),
                          parse_float=Decimal, parse_int=int)
        section = case["operation"]
        if section == "account":
            examples = [body]
        elif section in ("assets", "positions", "orders"):
            examples = body
        elif section == "order_by_id":
            section = "orders"
            examples = [body]
        else:
            continue
        if not isinstance(examples, list):
            examples = [examples]
        for example in examples:
            if not isinstance(example, dict):
                continue
            collected[section].append((case["id"], example))
            if section == "positions" and isinstance(example.get("usd"), dict):
                collected["positions.usd"].append((case["id"], example["usd"]))
            if section == "orders" and isinstance(example.get("legs"), list):
                for leg in example["legs"]:
                    if isinstance(leg, dict):
                        collected["orders.legs"].append((case["id"], leg))
    return collected


def section_table(section, rows):
    n = len(rows)
    names = sorted(set().union(*(set(item) for _, item in rows)))
    result = [
        f"## {section} — {len(names)} observed wire keys",
        "",
        f"Observed objects in current synthetic success cases: **{n}**. "
        "Conditional and future provider fields are included.",
        "",
        "| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |",
        "| --- | --- | ---: | ---: | ---: | --- |",
    ]
    for field in names:
        present = [(case_id, obj[field]) for case_id, obj in rows if field in obj]
        types = ", ".join(sorted({wire_type(value) for _, value in present}))
        null_count = sum(value is None for _, value in present)
        first_case = present[0][0] if present else "-"
        result.append(f"| `{field}` | {types} | {len(present)} / {n} | "
                      f"{null_count} | {n-len(present)} | `{first_case}` |")
    return result + [""]


def main():
    sections = records_by_section()
    lines = [
        "# Triparity: observed field-by-field synthetic wire inventory",
        "",
        "**Generated from the canonical Atalanta fixture set revision `1.0.0`** "
        "by `python3 tools/triparity/field_inventory.py`. "
        "This inventory describes **wire fields actually present in the shared "
        "synthetic response examples**, not a complete provider OpenAPI "
        "schema, and does not imply that Kirke or Medea accepts them.",
        "",
        "Each table reports JSON types, explicit nulls and omissions across "
        "sampled response objects. Financial decimal strings are listed as "
        "**strings** deliberately: JSON lexical type matters for decoder parity. "
        "The source JSON must never be parsed through a binary floating point.",
        "",
        "The companion [triparity audit](TRIPARITY_AUDIT.md) identifies "
        "per-language decoder behavior and decisions. The verified "
        "[fixture manifest](../contracts/alpaca-trading/v1/manifest.json) "
        "contains the full request/error expectations.",
        "",
    ]
    for name in ("account", "assets", "positions", "positions.usd", "orders", "orders.legs"):
        if name in sections:
            lines.extend(section_table(name, sections[name]))
    OUT.write_text(chr(10).join(lines).rstrip(chr(10)) + chr(10))
    print("Generated", OUT.name, "sections", len(sections),
          "field rows", sum(len(set().union(*(set(obj) for _, obj in rows))) for rows in sections.values()))


if __name__ == "__main__":
    main()
