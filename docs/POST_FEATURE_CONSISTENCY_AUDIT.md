# Post-feature consistency audit — Atalanta, Kirke and Medea

**Status:** review draft for [Atalanta #26](https://github.com/black-belt-badger/atalanta/issues/26) and [public Java API #5](https://github.com/black-belt-badger/atalanta/issues/5). **No API, domain, transport, build, dependency, or ADR changes are approved here.** Follow-on changes should be small, per-repository issues negotiated with the product owner (PO).

## Evidence and scope (13 November 2026)

| Project | Revision audited | Tests / observations |
| --- | --- | --- |
| Atalanta (Java 17) | `d6ec84a` on `origin/main`, after merged #39 | `mvn -q clean verify` with JDK 17: **239 tests / 25 suites, 0 failures/errors/skips** |
| Kirke (Haskell) | `d2997ec` on `origin/main` | Native decoder/status smoke: **23/26** of canonical Trading API cases; three previously known mismatches |
| Medea (Rust) | `f27d9f7` on `origin/main` | Same native decoder/status smoke: **23/26**; the same three previously known mismatches |

The shared fixture set is `alpaca-trading-read-only/1.0.0`, **26 synthetic cases**, with checksum verification. The [isolated smoke runner](../tools/triparity/README.md) copied test-only adapters into detached scratch worktrees at the above Haskell/Rust revisions. It ran **without real Alpaca orders, without credential use and without mutating either source repository**. It reported **no unexpected additional gaps** compared with [phase-1 findings](TRIPARITY_AUDIT.md). Its result does **not** prove full field-value normalization, native HTTP query construction, historical data parity or write-operation parity.

This audit adds the **newly merged** M4 historical Bars/Trades/Quotes and M5 paper Submit/Cancel operations to the earlier M3 read-only assessment. Evidence levels are differentiated:

- **Verified:** exercised Java tests or the portable native offline smoke.
- **Code-reviewed:** signatures, model/decoder implementation, docs and tests inspected, but not executed against a shared complete fixture in all three languages.
- **Decision pending:** intentional or possible difference requiring PO input.
- **Confirmed doc drift:** statements contradicted by the merged public API.
- **Schema/production hypothesis:** requires confirmation against provider schema or a sanitized real response before claiming a production bug.

## Endpoint inventory and parity boundary

| Operation | Atalanta Java 17 | Kirke Haskell CLI | Medea Rust CLI | Assessment |
| --- | --- | --- | --- | --- |
| `GET /v2/account` | `CompletionStage<Result<Account>>` | Typed account response and CLI | Typed account response and CLI | **Verified** core read shapes in portable cases |
| `GET /v2/assets` | `assets(AssetQuery)`, provider default equity | Explicit crypto-first CLI default | Explicit crypto-first CLI default | **Intentional default difference; fixture gaps** |
| `GET /v2/positions` | `positions()` | Typed portfolio | Typed portfolio | **Fixture gap** on mixed/null/omitted future fields |
| `GET /v2/orders` | `orders(OrderQuery)` with 11 filter concepts | Typed order filters | Typed order filters | **Verified** decoder/status smoke; query normalization still outstanding |
| `GET /v2/orders/{id}` | `order(UUID[,nested])`; HTTP 404 is `Ok(Optional.empty())` | Validated order ID, CLI not-found | Validated order ID, CLI not-found | **Verified** identity boundary; **decision pending** 404 surface |
| `GET /v1beta3/crypto/{loc}/bars` | `cryptoBars(query)`, bounded `cryptoBarsPages` | Historical bars command | Historical bars command | **Code-reviewed** equivalent provider concept; no new shared 3-language fixtures |
| `GET /v1beta3/crypto/{loc}/trades` | `cryptoTrades(query)`, bounded `cryptoTradesPages` | Historical trades command | Historical trades command | **Code-reviewed**; no full shared observations |
| `GET /v1beta3/crypto/{loc}/quotes` | `cryptoQuotes(query)`, bounded `cryptoQuotesPages` | Historical quotes command | Historical quotes command | **Code-reviewed**; no full shared observations |
| `POST /v2/orders` | `submitPaperOrder(ConfirmedPaperOrder)` | Explicit `--confirm` from dry run | Explicit `--confirm` and low-level gate | **Code-reviewed** equivalent intentional effect; no shared complete POST/response fixtures |
| `DELETE /v2/orders/{id}` | `cancelPaperOrder(UUID)` | `--confirm`; 204 request accepted | `--confirm`; 204 request accepted | **Code-reviewed** semantics; **decision pending** Java confirmation symmetry |

Java API source: [Atalanta.java](../src/main/java/bbb/atalanta/Atalanta.java). Related docs: [Bars](CRYPTO_BARS_API.md), [Trades](CRYPTO_TRADES_API.md), [Quotes](CRYPTO_QUOTES_API.md), [Submit](PAPER_SUBMIT_API.md), [Cancel](PAPER_CANCEL_API.md). Haskell/Rust CLI implementations retain their own independent revisions and terminal conventions; **do not equate a Java `Result` with literal CLI stdout or exit code**.

## High-priority concrete findings

### A1 — Shared synthetic decoder incompatibilities (contract blocker)

The native fixture runner still reports **three incompatible cases in each CLI**, all already independently reproduced in phase 1:

| Case IDs | Atalanta | Kirke and Medea | Classification / follow-up |
| --- | --- | --- | --- |
| `assets.default`, `assets.filtered_equity` | Accepts high-precision decimal `maintenance_margin_requirement` supplied as a **JSON string** | Decode it as JSON number only | **Confirmed fixture incompatibility.** Decide if provider contract requires string *and* number, then fix each CLI with exact decimal parsing; no `Double`/`f64` |
| `positions.open_mixed` | Accepts nullable/omitted conditional fields across mixed options/IPO/future synthetic records | Rejects at `asset_marginable: null` and other conditional field shapes | **Confirmed fixture incompatibility.** Define required vs nullable/omittable per field before correcting each CLI |

**Not yet established:** that all those synthetic variants are actually returned by Alpaca production. Preserve the fixture evidence, consult the provider schema and relevant sanitized samples, and do not silently edit fixtures to make the decoders pass.

### A2 — New M4/M5 parity is not yet proven through shared fixtures

The 26-case canonical pack covers **only five Trading GET endpoints**. Each repository has its own Bars/Trades/Quotes/Submit/Cancel tests, but there is **no single canonical, fully normalized cross-language matrix** for those five newer operations. Code-reviewed capability is not the same as independently verified triparity.

**Candidate next deliverable:** version a read-only historical M4 fixture pack with exact numeric/text/unknown-field expectations and pagination, plus a separate M5 **synthetic, no-credential, no-network** request/response/error pack for authorization, 200/201 Order, 204 DELETE, 404/422, 5xx and transport uncertainty. Independently execute native request construction and field-level output normalization; never issue a real write in CI.

### A3 — Java asynchronous result shape is intentionally heterogeneous

Trading GET/POST/DELETE and *single* historical pages return `CompletionStage<Result<T>>`. In contrast, bounded history scans return `CompletionStage<CryptoBarScan>` / TradeScan / QuoteScan, each a sealed algebra with:

- `Stopped(pages, Optional<continuation>)`, exhausted only when the token is absent;
- `Failed(pages, AlpacaError)`, preserving pages acquired before a later failure.

This is **not a demonstrated correctness defect**. Plain `Result.Err` would discard partial success; a `These`/`Those`-like algebra, a generic `PageScan<P>` or the current three sealed types can all represent it. However, consumers now need to learn two failure conventions, and the scan result duplicates three near-identical public ADTs. **Decide the shared vocabulary before an incompatible public record or result refactor.** Herkules experimentation does not automatically authorize replacing Atalanta's Java 17 API.

### A4 — POST and DELETE confirmation gates differ

`submitPaperOrder` requires a separately constructed `ConfirmedPaperOrder`, and the transport accepts only that type. `cancelPaperOrder(UUID)` is explicitly named and requires a UUID but **has no corresponding confirmation record**. Kirke and Medea use CLI `--confirm` for **both** writes, with a default dry run.

The Java distinction may be justified because an explicitly named method is already a deliberate effect; it is nevertheless a **real API design asymmetry**. Decide whether to keep it documented, or introduce a confirmable `PaperCancelRequest`/`ConfirmedPaperCancellation` (possible source compatibility burden). Never enforce CLI shell flags in a Java library.

### A5 — Open enums, missing/null, timestamps and 404 remain negotiated API choices

- Java domain records retain forward-compatible status/class/side values as **`String`**, while Kirke/Medea have typed Known/Other constructors for several fields. Public component changes are breaking; additive typed views may be safer.
- Java nullable record components plus `presentFields` / `supplied(wireName)` retain omission versus JSON null; Haskell `Maybe` and Rust `Option` generally merge those typed cases while raw CLI export can retain original JSON. The difference should be deliberate, not forced identical.
- Financial values use exact Java `BigDecimal`, while Kirke/Medea favor exact decimal **text** in parts of their public domain. Their semantic numeric comparison must not route through binary floats.
- Java response timestamps frequently retain provider RFC3339 spelling; historical request filters retain date/offset string form and compare instants. Avoid changing wire spelling or nanosecond precision without tests.
- `order(UUID)` treats 404 as `Result.Ok(Optional.empty())`; CLIs present a nonzero not-found outcome. This can be *semantic* parity if the shared normalization represents missing order distinctly; matching literal UI behavior is not required.

**Decision pending**, not yet approved source or binary breaking changes.

## Atalanta internal consistency and code hygiene

| Concern | Code observation | Classification | Candidate action after approval |
| --- | --- | --- | --- |
| M4 duplicated request validation | `CryptoBarQuery`, `CryptoTradeQuery` and `CryptoQuoteQuery` repeat 1–30 symbols, location, RFC3339/date validation, inclusive bounds, 1–10,000 limit, pagination token checks, `Sort` and builders (about **364 lines** combined) | **Maintainability**, no failing tests | Extract **package-private** validation helpers if it removes drift, retaining the public three typed records and distinct bar timeframe |
| M4 duplicated bounded scan machinery | Public `CryptoBarScan`/TradeScan/QuoteScan and the `Atalanta` page traversal methods have near-isomorphic structure; cap is 1–20; no token-count assumption | **Maintainability/API discussion**, not a bug | Keep current public signature until PO chooses whether a generic parameterized algebra helps; internal generic helper is lower-risk |
| Large historical backfills | A bounded scan can retain up to **20 × 10,000 = 200,000 observations** (across all symbols) plus immutable extension maps | **Known memory trade-off**, not unbounded | Prefer caller-controlled single-page iteration for DB/JSONL ingestion; consider a callback/iterator/publisher API only if profiling justifies it |
| Writes and uncertain outcomes | One authenticated, fixed-host PAPER POST or UUID DELETE; no application-level retries. Submission distinguishes 422 and unknown-after-5xx/timeout/malformed response. Cancellation success means **204 request accepted**, not terminal canceled | **Follows paper safety target**, verified by Java offline tests | Document reconciliation procedure clearly; do not add hidden retries or imply exactly-once |
| Strict provider fidelity | Current crypto order submission intentionally supports only market/limit; Alpaca also documents stop-limit. Asset-specific min-size/precision beyond request validation is provider-enforced | **Intentional M5 scope**, not a defect | Separate future scope issue only if users actually need stop-limit |
| Documentation drift | [HTTP_TRANSPORT.md](HTTP_TRANSPORT.md) still says no public `Atalanta.account()` endpoint exists; [package-info.java](../src/main/java/bbb/atalanta/package-info.java) says public API is yet to be defined and only read-only endpoints exist | **Confirmed S3 documentation defect** | Small documentation-only fix prior to release |
| Release assumptions | `0.1.0-SNAPSHOT`, Java 17, direct Maven artifact not yet published, [release #13](https://github.com/black-belt-badger/atalanta/issues/13) open | **Release work outstanding** | Consumer compilation/test smoke, checked coordinates, README/Javadoc consistency and release decisions |

A public source-compatible evolution should prefer adding methods and small private helpers to replacing record component types or sealed variants before a release policy is agreed.

## Architecture status is not the same as compliance failure

Metis `ADR-0002` (independent Alpaca client) remains **Accepted**: Atalanta has separate repository/module ownership and a fixed paper-only Trading API endpoint. `ADR-0003` through `ADR-0007` remain **Proposed**, with `0007` explicitly exploratory (checked 2026-11-13). Those proposals describe component boundaries, event semantics, functional Java, Java nullness annotations/Lombok, reusable result types and pure state transitions.

Atalanta uses **Java 17** records and sealed `Result`/`AlpacaError`; no public Jackson types or mandatory Metis/Vert.x runtime. It currently does **not** use JSpecify nullness tooling, Lombok or Herkules/`Those`. That absence is **not** a violation of proposed decisions. Importing Herkules, lifting Java runtime version, changing `CompletionStage` or exposing richer error algebras requires explicit compatibility review rather than an audit-only rewrite.

## PO decisions, suggested priority order (none approved yet)

1. **Provider decoder compatibility (P1):** accept quoted asset decimals and sparse/nullable position fields? Confirm provider shapes, then issue **small per-repo tests/fixes** in Kirke and Medea.
2. **M4/M5 shared contracts (P1):** authorize a fixture pack and native normalized request/response checks for Bars, Trades, Quotes, Submit and Cancel; avoid real write calls.
3. **Public Java partial-result algebra (P1 before library release):** retain three `Crypto*Scan` sealed types, add a private generic implementation, or adopt a reusable generic/`Those` type? Identify source/binary migration.
4. **Mutation safety symmetry (P1 before library release):** is explicit Java `cancelPaperOrder(UUID)` sufficient, or should both writes require a separate confirmation value? Preserve read-only behavior.
5. **Intentional cross-language differences (P2):** document equity versus crypto assets default, Java 404 Optional versus CLI error, open String versus Known/Other, null-vs-absent and offset text, and when typed views are useful.
6. **Low-risk deduplication and documentation (P2/P3):** correct stale transport/package docs; consider internal query/pagination helpers **after** compatibility decisions. No framework rewrite.
7. **Release-readiness #13 (P2):** ship a tested Java 17 consumer example and Maven distribution after unresolved public API shape decisions are settled.

**Do not close [#26](https://github.com/black-belt-badger/atalanta/issues/26) merely on merge of this report.** To finish the full audit, negotiate deviations with the PO, run normalized native and wire-level regression tests across all intended operations, file targeted follow-on issues with owner and acceptance criteria, and document shared fixture versioning without lockstep Java/Haskell/Rust release numbers.
