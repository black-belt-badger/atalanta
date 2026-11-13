# Triparity smoke test adapters (audit-only)

These test-only adapters consume the same **synthetic, checksum-verified** [26-case Atalanta contract pack](../../contracts/alpaca-trading/v1/README.md) through the existing Kirke/Haskell and Medea/Rust decoders. They are deliberately **not installed** into either CLI's production code. This is the first evidence-gathering stage for [Atalanta triparity #26](https://github.com/black-belt-badger/atalanta/issues/26).

## Run

First, ensure each local repository has fetched a recent `origin/main`, then, from Atalanta's root:

```sh
bash tools/triparity/run-smoke.sh \
  /home/marek/Business/BlackBeltBadger/repos/kirke \
  /home/marek/Business/BlackBeltBadger/repos/medea

# Optionally retain the entire synthetic test logs
TRIPARITY_LOG_DIR=/tmp/triparity-logs \
  bash tools/triparity/run-smoke.sh /path/to/kirke /path/to/medea

# Test the audit's verdict parser without building other languages
python3 tools/triparity/test_assess.py

# Regenerate the 155-row observed wire field inventory (no binary floats)
python3 tools/triparity/field_inventory.py
```

**Dependencies:** local `git`, `python3`, `stack` and `cargo` with dependencies already available; no network access to Alpaca, no provider credentials. The script operates on locally fetched `origin/main` commit objects; it does **not** call `git fetch` or use GitHub. Stack/Cargo could download build dependencies if the local toolchain cache is empty, so run offline/with warmed caches if strict no-internet execution matters.

The shell script constructs temporary **detached Git worktrees**, inserts Hspec/Rust tests **only in those scratch trees**, explicitly removes `APCA_API_KEY_ID` and `APCA_API_SECRET_KEY`, invokes the existing native test runners and removes its own temporary worktrees afterwards. Existing development trees, branches, uncommitted changes and real customer/account data are untouched. It does **not** execute `kirke` or `medea` as an authenticated trading CLI and cannot issue order POST/DELETE.

The shell tool returns success **only if all 26 fixture IDs yield one PASS/MISMATCH marker per language, no test/build fails unexpectedly, and every observed mismatch is listed in [known-gaps.json](known-gaps.json)**. It permits previously known gaps to become passes, but **new** gaps or a missing case marker fail the audit baseline.

## Verified initial baseline (2026-11-13)

| Client | Commit | Native decoder/status acceptance |
| --- | --- | --- |
| Kirke | `d2997ec` | **23 of 26**, three known gaps |
| Medea | `f27d9f7` | **23 of 26**, same three fixture IDs |
| Atalanta | `cab230d` | **26 of 26** through its existing public asynchronous Java runner |

- `assets.default` / `assets.filtered_equity` — deprecated `maintenance_margin_requirement` provided as an exact decimal JSON **string**, whereas both CLI decoders require a JSON **number**.
- `positions.open_mixed` — one of six positions has null `asset_marginable`; other variants contain omitted or null conditional pricing/margin fields. Both CLI decoders reject the collection.
- The fixture `order_by_id.mismatched_id` correctly fails the **high-level identity-validated lookup** in all three implementations; a raw standalone `decodeOrder` is insufficient to test that requirement.

## Scope and limitations

The smoke adapters assert **response shape acceptance versus rejection and HTTP status boundaries**, including the by-ID identity check. They do *not* yet perform all 26 of Atalanta's normalized JSON-pointer field/value assertions on Haskell/Rust values, inspect complete query parameter serialization under the CLIs, or simulate transport failures and timeouts. The table above is **not an unqualified 23/26 triparity score**; it is a decoder/status compatibility score for this synthetic pack.

For the parameter-by-parameter and error matrix, provider field inventory, known discrepancies and pending PO decisions, see [TRIPARITY_AUDIT.md](../../docs/TRIPARITY_AUDIT.md) and [TRIPARITY_FIELD_INVENTORY.md](../../docs/TRIPARITY_FIELD_INVENTORY.md).

## Maintaining the pack

The canonical manifest, source fixture files, SHA checksums and `1.0.0` revision remain under `contracts/alpaca-trading/v1/`. Adapters read the **same canonical bytes**, not copies with potentially drifting hashes. Production fixes should add permanent native regression tests and explicit per-language PRs; update `known-gaps.json` only when a reviewed change resolves or reclassifies a case. Do not silently mutate the source fixtures to satisfy an existing decoder, and do not couple Haskell/Rust release versions to Java's.
