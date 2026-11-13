# Alpaca Trading API — portable read-only contract fixtures

**Fixture set:** `alpaca-trading-read-only` · **Format:** `1` · **Revision:** `1.0.0`
**Canonical candidate location:** `contracts/alpaca-trading/v1/` in Atalanta.

This folder is a **self-contained, language-neutral set of synthetic wire contracts** for the five initial Alpaca Trading API reads. It contains only static JSON, a manifest, integrity hashes, and explanatory Markdown. It has **no Java source, compilation dependencies, executable hooks, real account records, or credentials**. Kirke (Haskell) and Medea (Rust) can copy or vendor the entire folder and implement their own manifest runner.

This is an **Atalanta-maintained candidate contract pack**, not a claim that Kirke/Medea already consume or pass it. Actual triparity findings and negotiated language-specific deviations belong in [Atalanta #26](https://github.com/black-belt-badger/atalanta/issues/26) after endpoint work.

## Why not a fourth repository?

Evaluate the maintenance costs before creating another repo. Our current choice for M3 is to keep one canonical, independently versioned **directory**, so it can be copied or pinned via a Git commit. Creating another repository, Git submodule, or generated artifacts now would add setup and cross-repo CI overhead without evidence of value. If three clients regularly drift, revisit this at the triparity audit; a standalone contract repo may then be justified.

The existing per-endpoint test fixtures were moved here from `src/test/resources/fixtures/` rather than duplicated. Maven adds this directory to *test resources*, so the original 115 JUnit tests keep using their same `/fixtures/*.json` classpath paths. The 26 new portable cases also consume the same fixtures. A fix to a canonical fixture is seen by all tests.

## Structure

- `manifest.json`: versioned list of portable HTTP request/response/outcome cases.
- `fixtures/*.json`: synthetic HTTP response bodies (success, empty, future/legacy field, and error cases), never real brokerage data.
- `SHA256SUMS`: integrity hashes of the manifest and fixture bytes. Validate with `sha256sum -c SHA256SUMS` from this directory.
- `README.md`: neutral contract and change policy.

A case in the manifest has the following shape (fields shown with synthetic values):

```json
{
  "id": "order_by_id.not_found",
  "operation": "order_by_id",
  "input": {"id": "11111111-2222-4333-8444-555555555555"},
  "request": {
    "method": "GET",
    "path": "/v2/orders/11111111-2222-4333-8444-555555555555",
    "rawQuery": null
  },
  "response": {
    "status": 404,
    "body": "fixtures/error-private.json"
  },
  "expected": {"outcome": "absent"}
}
```

**Important:** `input` is a language-neutral set of caller arguments, not a Java class name or Haskell/Rust syntax. `request` is the **expected HTTP wire request**, with unmodified percent-encoding and query-parameter order; `rawQuery: null` means no query string. `response` is the provider status/body supplied by the test stub. `expected` describes the **normalized observable client outcome**, not serialized Java records, CLI colors, or a raw HTTP echo.

### Normalized results

- `outcome: "ok"` — success; singleton results appear under `/value`; collections under `/items` with optional `count`.
- `outcome: "absent"` — a successful lookup found no object (currently `GET /v2/orders/{id}` on HTTP 404).
- `outcome: "error"` — operational/decoding failure, with an `error` category such as `Authentication`, `RateLimited`, `HttpFailure`, or `InvalidResponse`.
- Optional `checks` are JSON Pointer assertions into a **test-only normalized view**. They compare values, not implementation type constructors.
- Monetary and quantity fields in the normalized view are **decimal strings** (including trailing precision), never binary floating-point. Real provider JSON numbers may remain numeric in the response fixtures; adapters must parse and preserve them exactly.
- Null, false, empty and absent fields are different; normalized `/value/supplied/...` or `/items/0/supplied/...` fields make selected field-presence semantics testable.
- Unknown provider extension fields are checked via `/extensions` when important. Each adapter must preserve or explicitly document differences where its public API does not expose them.

The Java test runner's projection deliberately includes just the fields needed to demonstrate parity. A Haskell/Rust runner may use different internal representations but must produce equivalent observed values for these assertions. No language-specific constructor name, exception class, JSON library node, or CLI output color is part of this portable input.

**Caveat:** the error-category strings and the 404/absence rule are currently based on Atalanta's agreed API semantics. A different native `Result`/CLI rendering is an intentional difference only if reviewed and documented in the triparity audit; this directory does not silently decree the shape of their public APIs.

## Coverage and extension

See [contract coverage matrix](../../../docs/CONTRACT_PARITY.md) for the cases by endpoint, input/default behavior, exact decimal fidelity, null/unknown fields, nesting, error categories and untested behavior.

All case IDs are stable. To extend the pack:

1. Use **synthetic UUIDs, balances, timestamps and credentials**. Public ticker symbols may appear as examples; never record real or proprietary account responses.
2. Prefer adding one new canonical response fixture to changing an existing value for convenience. Never parse a precise JSON decimal through Python `float` or Java `double`.
3. Add a manifest case with `id`, `operation`, `input`, `request`, `response`, `expected`. Update the relevant runner.
4. Rebuild collection fixtures from the **original source fixture text**, not via lossy JSON parse/serialize; ensure options leg ratios and unknown numeric precision remain intact.
5. Run all Atalanta tests using JDK 17. Recalculate `SHA256SUMS` for every changed manifest/fixture with `sha256sum manifest.json fixtures/*.json > SHA256SUMS` and verify it.
6. Increment the fixture-set revision separately from Atalanta/Kirke/Medea release versions. Minor revision for additive compatible cases, patch for corrections that do not change semantic meaning, major for changed interpretation/required observations.

Consumers can pin a **specific Git SHA plus fixture revision**. Sharing this directory does **not** imply coordinating language release versions or deploying all three clients simultaneously.

## Security and usage boundary

These are synthetic **paper Trading API** requests; this pack does not authorize live trading or supply real credentials. CI does not connect to Alpaca. No endpoint is executable here. The portable manifest describes fixture-driven observations, not API calls to perform automatically against the internet.
