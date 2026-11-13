# Atalanta–Kirke–Medea triparity audit — phase 1 evidence and decisions

**Issue:** [Atalanta #26](https://github.com/black-belt-badger/atalanta/issues/26). **Status: review draft; no cross-language API decisions approved.** This report compares the **five common, read-only Alpaca paper Trading API endpoints**, not the extra crypto market-data or paper write operations implemented by the CLIs.

**Checked revisions (2026-11-13):** Atalanta `cab230d` (audit #28 on main), Kirke `d2997ec` (cancel #56 on main), Medea `f27d9f7` (paper submit #56 on main). Fixture set `alpaca-trading-read-only/1.0.0` (26 synthetic cases, checksum-verified). See [portable manifest](../contracts/alpaca-trading/v1/manifest.json), [existing Java normalized test](../src/test/java/bbb/atalanta/SharedTradingContractTest.java), [field-by-field wire inventory](TRIPARITY_FIELD_INVENTORY.md) and [Atalanta internal audit](INTERNAL_API_AUDIT.md).

## What was actually executed

- **Atalanta, public Java library:** Java 17 `mvn -q clean verify` passed **143 tests** (including 26 portable cases through the injected asynchronous HTTP sender), with normalized JSON pointer assertions and request method/path/raw query matching. This was verified in the preceding internal audit and is the known baseline.
- **Kirke, native Haskell:** experimental `Alpaca.PortableFixtureSmokeSpec` uses the canonical 26 response bodies, runs real `decodeAccount` / `decodeAssets` / `decodePositions` / `decodeOrders` and the **higher-layer** `decodeOrderLookup` (which validates the requested ID), plus the pure HTTP status interpreter.
- **Medea, native Rust:** experimental `portable_contract_smoke` invokes those same real native decoders, including `decode_order_lookup` for the ID check, on the same 26 bodies and status codes.
- The reproducible [`tools/triparity/run-smoke.sh`](../tools/triparity/run-smoke.sh) creates **isolated detached worktrees** of locally fetched `origin/main` for both CLI repos, injects test-only adapters, **unsets Alpaca credential environment variables**, runs native offline tests, checks case IDs and known gaps, then removes only those scratch worktrees. No provider requests, POST, DELETE or changes to real worktrees.
- This is **provider decode/status smoke**, NOT complete semantic/normalized field conformance for CLI output, NOT all 26 original wire request assertions on those CLIs, and NOT evidence about real account availability. The known-gap baseline is machine-readable in [`known-gaps.json`](../tools/triparity/known-gaps.json). The script rejects *new* gaps or missing case markers.

| Portable cases | Java normalized runner | Kirke native decoder/status | Medea native decoder/status |
| --- | ---: | ---: | ---: |
| Account (5) | 5/5 | 5/5 | 5/5 |
| Assets (6) | 6/6 | **4/6** | **4/6** |
| Positions (3) | 3/3 | **2/3** | **2/3** |
| Orders (6) | 6/6 | 6/6 | 6/6 |
| Order by ID (6) | 6/6 | 6/6 | 6/6 |
| **Total (26)** | **26/26** | **23/26** | **23/26** |

**The failures are three canonical *cases*, representing two kinds of decoder incompatibility per CLI.** An earlier raw-only Haskell test falsely flagged order-ID mismatches; testing `decodeOrderLookup` rather than `decodeOrder` removes that false positive. The table above reports the corrected boundary.

## Confirmed fixture gaps (blockers for full triparity)

| Case IDs | Observed behavior | Why it matters | Suggested follow-up (not yet approved) |
| --- | --- | --- | --- |
| `assets.default`, `assets.filtered_equity` | Both Kirke and Medea reject `maintenance_margin_requirement: "29.12500000000000000000001"`. Kirke's field is `Maybe Scientific`, Rust uses `Option<serde_json::Number>`; these expect JSON *number*. Atalanta accepts a numerical string through exact `BigDecimal`. The same fixture succeeds in a diagnostic Rust run when only the field is changed to a JSON numeric literal. | A quoted high-precision deprecated margin field can invalidate an **entire asset listing**, even when all other assets are valid. JSON number versus string matters more than formatting. | Agree whether provider compatibility requires **both string and number**; if yes, add a precise, non-floating parser in each CLI and regress both variants. Confirm upstream schema and observed provider reality before labeling an Alpaca contract violation |
| `positions.open_mixed` | Kirke and Medea reject conditional/null fields in option/IPO/future position shapes. Kirke first fails at `asset_marginable: null`, Medea likewise. Rust per-element diagnostics also show an IPO row with nullable string pricing and a future-shape row **omitting** `asset_marginable`. Java accepts all. | One sparse position can make a **whole portfolio read fail**. The shared 6-position synthetic response intentionally covers IPO nulls, options and unknown future values. | Revisit which of 22 position fields are truly unconditional; model conditional fields with explicit optionality, retain null/absent meaning where required, and test every mixed row. Avoid guessing or manufacturing zero prices |

**Classification:** *confirmed shared-fixture decoder incompatibility*; **production impact/severity awaits provider schema and real response confirmation**. These are not merely different CLI renderers. **No fixes are applied in this audit PR.** Detailed per-field observed missing/null counts are in [TRIPARITY_FIELD_INVENTORY.md](TRIPARITY_FIELD_INVENTORY.md).

Relevant code: [Kirke Asset decoder](https://github.com/black-belt-badger/kirke/blob/d2997ec/src/Alpaca/Domain/Asset.hs), [Kirke Position decoder](https://github.com/black-belt-badger/kirke/blob/d2997ec/src/Alpaca/Domain/Position.hs), [Medea Asset decoder](https://github.com/black-belt-badger/medea/blob/f27d9f7/src/assets.rs), [Medea Position decoder](https://github.com/black-belt-badger/medea/blob/f27d9f7/src/positions.rs), [Java exact decoders](../src/main/java/bbb/atalanta/AssetDecoder.java).

## Endpoint and request-parameter matrix

**Legend:** `E` equivalent semantics for tested inputs, `I` intentional surface difference, `D` decision pending, `G` identified gap or untested cross-language behavior. **These are code-reviewed observations**, not all independently asserted through the portable runner's HTTP stub.

| Endpoint / parameter | Atalanta Java library | Kirke Haskell CLI | Medea Rust CLI | Assessment |
| --- | --- | --- | --- | --- |
| Account `GET /v2/account` | `account()` no query | `account` no query | `account` no query | **E**; asynchronous typed result vs synchronous CLI effect is **I** |
| Assets `asset_class` default | omitted -> provider **us_equity** | explicit `crypto` CLI default | explicit `crypto` CLI default | **I**, semantic **non-parity** on default invocation; must be explicit in docs and shared test inputs |
| Assets `asset_class` explicit | validated open String token | 10-member closed request enum | 10-member closed request enum | **D** for future unknown request classes (response enum types remain open) |
| Assets `status` | omit / active / inactive | omit / active / inactive / CLI all | omit / active / inactive / CLI all | **E** for provider states, **I** CLI `all` handling |
| Assets `exchange` | nullable list; equity/index restrictions | typed 9-member CSV allowlist | typed 9-member CSV allowlist | **E** for supported values; **D** future new exchange filters |
| Assets `attributes` | nullable list of validated tokens | typed 8-member CSV allowlist | typed 8-member CSV allowlist | **E** for supported values; **D** future attribute filter tokens |
| Positions `GET /v2/positions` | no query | no query | no query | **E** request, **G** conditional response decoding |
| Orders `status` | omit -> provider open | emits `status=open` | emits `status=open` | **E** provider semantics; **I** raw query |
| Orders `limit` | omit -> provider 50; 1–500 | omit -> provider 50; 1–500 | omit -> provider 50; 1–500 | **E** |
| Orders `direction` | omit -> provider desc | emits `direction=desc` | emits `direction=desc` | **E** provider semantics; **I** raw query |
| Orders `after` / `until` | `Instant` exclusive bounds | validated timezone-qualified timestamp | validated timezone-qualified timestamp | **E** interval concept, **D** offset lexical preservation and normalized exact URI text |
| Orders `nested` | tri-state nullable Boolean (omit/true/false) | Boolean flag (omit/true) | Boolean flag (omit/true) | **I/D** — explicit false can be sent by Java but not these CLI switches |
| Orders `symbols` | validated list, CSV | validated CSV | validated CSV | **E** normal input, URI byte ordering **G** |
| Orders `side` | String buy/sell | typed buy/sell | typed buy/sell | **E** supported values; type representation **I** |
| Orders `asset_class` | validated open list | typed known values + all | typed known values + all | **D** future request class extensibility |
| Orders `before_order_id` / `after_order_id` | distinct nullable UUID anchors | validated canonical UUID anchors | validated canonical UUID anchors | **E** exclusive pagination intent; verify boundary/time combinations in native fixture runner |
| By-ID `order_id` | `java.util.UUID` | validated `OrderId` | validated `OrderId` | **E** identity semantics; **I** case-normalization representation |
| By-ID `nested` | omit, true, **explicit false** | omit/true | omit/true | **I/D** same issue as orders nested flag |

Source evidence: [Atalanta API](../src/main/java/bbb/atalanta/Atalanta.java), [AssetQuery](../src/main/java/bbb/atalanta/AssetQuery.java), [OrderQuery](../src/main/java/bbb/atalanta/OrderQuery.java), [Kirke request/client](https://github.com/black-belt-badger/kirke/blob/d2997ec/src/Alpaca/Client.hs), [Medea request/client](https://github.com/black-belt-badger/medea/blob/f27d9f7/src/client.rs), [canonical request matrix](../contracts/alpaca-trading/v1/manifest.json).

**Important:** the canonical `request.rawQuery` assertions deliberately capture Atalanta's omitted defaults and parameter ordering. They should **not** be copied blindly as CLI expectations when explicit provider-equivalent query params are part of an approved CLI default. A normalization rule should compare decoded query multimap and effective provider defaults; raw URI serialization is tested separately for injection/encoding safety.

## Response model and field-level comparison

The generated [155-row synthetic wire inventory](TRIPARITY_FIELD_INVENTORY.md) lists every observed top-level, USD-nested and leg-nested key with JSON lexical types, null counts, omitted counts and case provenance. For **published schema** coverage, use each language's independently audited endpoint field matrix in addition to those fixture observations.

| Area | Java | Haskell | Rust | Assessment |
| --- | --- | --- | --- | --- |
| Account: identity, status, numeric fields | `UUID`, open `String` statuses, exact nullable `BigDecimal` | `Text` identity, typed known/future status, precise provider numeric `Text` | `String` identity, typed known/future status, exact provider numeric `String` | **E** decoded basic cases; **I** representation, no financial `Double`/`f64` |
| Asset: 19 documented properties | `UUID`, strings, `BigDecimal`; presence/unknown extensions retained | typed known/Other enums; `Text` decimals, deprecated `Scientific` | typed Known/Other enums; `String` decimals, deprecated JSON `Number` | **G** quoted deprecated numeric variant in two cases |
| Positions: 22 documented fields incl. USD | `BigDecimal` values nullable by actual provider shape; presence/unknown extensions | known/Other enums, fixed `Text` and `Bool` for many fields | known/Other enums, required `String` and `bool` for many fields | **G** options/IPO/future nullable/omitted combinations |
| Orders: published 37 / 35 leg properties | `BigDecimal`, open String enums, `List<Order>` legs, field presence and extensions | typed open enums, exact `Text` quantities, one-level typed legs | typed open enums, exact `String` quantities, one-level typed legs | **E** six smoke cases; **D** known/Other vs String public API, deeper field equality not yet run |
| Single-order identity | Validates returned UUID against requested UUID | `decodeOrderLookup` validates case-insensitively | `decode_order_lookup` validates case-insensitively | **E** boundary verified on mismatched canonical fixture |
| Missing / null / unknown | `presentFields` + nullable values + deep immutable provider extras | `Maybe` loses distinction between absent and null in typed view; `--out` retains raw JSON | `Option` loses distinction in typed view; `--out` retains raw JSON | **I/D**: normalized semantic view vs exact original export |
| Financial syntax and unknown JSON | Exact numerical semantics in `BigDecimal`/immutable maps; no raw HTTP bytes in API | Private raw JSON saved unchanged by `--out` | Private raw JSON saved unchanged by `--out` | **E** numeric values for tested values; **I** lexical preservation and consumer API |

No code here imposes Java terminal colors, shell completion, file output paths or exit codes on a library. Conversely, native CLI presentation is not a substitute for valid typed decoding of the same provider response.

## Error and completion semantics

| Situation | Atalanta (Java) | Kirke (Haskell) | Medea (Rust) | Classification |
| --- | --- | --- | --- | --- |
| HTTP 401 | `Result.Err(Authentication)` | `UnexpectedStatus 401`, sanitized CLI text | `ClientError::UnexpectedStatus(401)`, sanitized CLI text | **E** observable auth rejection, **I** type constructors |
| HTTP 403 | `Err(Forbidden)` | sanitized HTTP 403 | sanitized HTTP 403 | **E/I** |
| HTTP 429 | `Err(RateLimited)` | sanitized HTTP 429 | sanitized HTTP 429 | **E/I**; typed retry metadata unresolved |
| HTTP 503 | `Err(HttpFailure(503))` | sanitized HTTP 503 | sanitized HTTP 503 | **E/I** |
| By-ID HTTP 404 | `Ok(Optional.empty())` | nonzero `order not found` error | nonzero `order not found` error | **D** native contract differs; same underlying absence only with an explicit normalization rule |
| Invalid 200 JSON/shape | `Err(InvalidResponse)` | nonzero generic sanitized response error | nonzero generic sanitized response error | **E** synthetic smoke; do not expose provider body |
| Valid 200 wrong order ID | `Err(InvalidResponse)` | ID mismatch error from lookup boundary | ID mismatch error from lookup boundary | **E** corrected smoke uses lookup boundary |
| Invalid local parameter | synchronous Java argument validation exception | command-line parse failure (before I/O) | Clap/validator failure (before I/O) | **I/D** different invocation mode |
| Timeout / broken connection | typed `Timeout` / `TransportFailure`; unexpected programming exceptions complete stage exceptionally | `ConnectionFailed`, no raw exception body | `ConnectionFailed`, no raw exception body | **I/D** timeout distinction not tested in portable pack |
| Successful empty list | `Ok(List.of())` | valid empty list and human output | valid empty list and human output | **E** |
| Partial pagination failure | one Java page per call, no accumulation | one request for trading order list | one request for trading order list | **G**, not addressed by this one-shot pack |

**Paper-only safety:** all three trading clients use fixed Alpaca paper hosts, credential headers, validated path/query construction, redirect protections, sanitized provider error surfaces and no automatic retry for write operations. Kirke/Medea now have extra **paper-only** opt-in POST/DELETE endpoints, intentionally outside this read-only comparison; their defaults are offline dry runs.

## PO decision queue / implementation sequence

No choice below is approved just by merging this report.

1. **Compatibility defects first (P1):** accept quoted decimal or number for deprecated asset margin requirement? Agree option/IPO/future position nullable field semantics from provider evidence. Then create **one small correction issue per language/area**, with the exact fixture IDs as regression tests and no lossy floating-point conversion.
2. **Error algebra (P1):** retain Java `Ok(Optional.empty())` for by-ID 404 versus CLI nonzero not-found, or introduce explicitly named alternatives? Do not change either behavior without a PO decision and migration impact analysis.
3. **Request defaults (P1):** is crypto-first CLI `assets` versus provider `us_equity` default an approved intentional product difference? Keep explicit in documentation and normalized contracts.
4. **Model shape (P2):** Java open `String` statuses vs typed Known/Other in Kirke/Medea; field presence, unknown JSON retention and typed timestamps. Favor additive views until public compatibility is agreed.
5. **Fixture depth (P2):** implement **native normalization and actual HTTP request builders** against all 26 portable cases, not just decoder/status smoke. Then extend with distinct network timeout, unknown-typed-field, conditional-field and erroneous token cases.
6. **Maintenance (P2):** pin fixture revision + SHA hashes in independent per-language CI; do not lock release numbers or require a fourth repository. Add only a compatible fixture revision after stakeholder review.
7. **Presentation (deferred):** colors, Unix streams, shell completion and diagnostic UX belong in Kirke/Medea CLI issues, not Atalanta's Java API.

**Audit status:** phase 1 has reproduced concrete provider fixture gaps and provided a repeatable offline method. [Atalanta #26](https://github.com/black-belt-badger/atalanta/issues/26) should **remain open** until PO decisions, per-repo fixes and full native normalized request/response acceptance are done. [Atalanta internal audit #25](https://github.com/black-belt-badger/atalanta/issues/25) and initial fixture pack #7 are already completed.
