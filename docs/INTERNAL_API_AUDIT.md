# Atalanta #25 — Internal API and ADR audit (review draft)

**Baseline:** Java 17, commit `be774ce`, five read-only Alpaca paper Trading API methods. **Review only:** this document approves no code refactoring, API breakage, new library dependency or ADR status change.

**Verified 2026-11-13:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -q clean verify` — **143 tests in 16 suites, zero failures/errors/skips**. The existing `alpaca-trading-read-only/1.0.0` fixture pack has **26 portable contract cases** and a Java [offline contract runner](../src/test/java/bbb/atalanta/SharedTradingContractTest.java). Its parity in Haskell and Rust has **not** yet been verified. See [manifest](../contracts/alpaca-trading/v1/manifest.json) and [coverage matrix](CONTRACT_PARITY.md).

Assessment vocabulary: **Follows**, **Intentional difference**, **Decision pending**, **Documentation defect**, **Not applicable**. Severity: S0 = privacy/security/data loss, S1 = confirmed semantic correctness, S2 = consumer/API compatibility or missing evidence, S3 = docs/maintainability. **No S0/S1 runtime bug was established by this audit or the green offline suite.**

## Endpoint-by-endpoint assessment

| Operation | Current public contract | Assessment / relevant evidence |
| --- | --- | --- |
| `GET /v2/account` | `account(): CompletionStage<Result<Account>>`, required UUID/status, exact `BigDecimal`, nullable fields plus `AccountDetails.presentFields` and immutable extensions | **Follows**, S2 status typing decision. [API](../src/main/java/bbb/atalanta/Atalanta.java#L34-L38) · [model](../src/main/java/bbb/atalanta/Account.java) · [tests](../src/test/java/bbb/atalanta/AtalantaAccountTest.java) · [field matrix](ACCOUNT_API.md) |
| `GET /v2/assets` | `assets()` honors **provider** default `us_equity`, unlike the crypto-first CLIs; `assets(AssetQuery)` has status, class, exchange, attributes; exact decimals and presence metadata | **Follows / intentional CLI difference**, S2 string filters. [query](../src/main/java/bbb/atalanta/AssetQuery.java) · [model](../src/main/java/bbb/atalanta/Asset.java) · [tests](../src/test/java/bbb/atalanta/AtalantaAssetsTest.java) · [field matrix](ASSETS_API.md) |
| `GET /v2/positions` | `positions(): CompletionStage<Result<List<Position>>>`, empty list valid, nested `PositionUsd`, exact quantities and immutable extension fields | **Follows**, S2 open string side/class. [model](../src/main/java/bbb/atalanta/Position.java) · [decoder](../src/main/java/bbb/atalanta/PositionDecoder.java) · [tests](../src/test/java/bbb/atalanta/AtalantaPositionsTest.java) · [field matrix](POSITIONS_API.md) |
| `GET /v2/orders` | `orders()` honors provider `open`/50/`desc` defaults; `OrderQuery` exposes 11 query concepts, validates mutually exclusive UUID/time cursors; order/legs model exact decimals and nulls | **Follows**, S2 date/time and enum representation. [query](../src/main/java/bbb/atalanta/OrderQuery.java) · [decoder](../src/main/java/bbb/atalanta/OrderDecoder.java) · [tests](../src/test/java/bbb/atalanta/AtalantaOrdersTest.java) · [field matrix](ORDERS_API.md) |
| `GET /v2/orders/{id}` | `order(UUID)` omits `nested`; `order(UUID,boolean)` sends explicit true/false; verifies provider ID; HTTP 404 returns `Result.Ok(Optional.empty())` | **Intentional API/CLI difference; decision pending**, S2. Kirke/Medea display nonzero not-found. [public API](../src/main/java/bbb/atalanta/Atalanta.java#L85-L117) · [tests](../src/test/java/bbb/atalanta/AtalantaOrderByIdTest.java) · [field matrix](ORDER_BY_ID_API.md) |

**Scope note:** Atalanta currently targets these five **read-only Trading API** operations. Paper POST/DELETE and Crypto Bars/Trades/Quotes exist or are in progress in Kirke/Medea, but are **not** omissions from the selected Atalanta M3 contract. They need separate scope decisions, not silent addition to this audit.

## ADR-by-ADR matrix (binding status matters)

| Source | Status at review | Observed Atalanta compliance / decision |
| --- | --- | --- |
| Metis ADR-0002 — independent Alpaca client | **Accepted** | **Follows.** Separate Maven artifact, no Metis/Ariadne dependency, paper-only `Atalanta.paper` factory and internal HTTP test seam. [factory](../src/main/java/bbb/atalanta/Atalanta.java) · [transport](../src/main/java/bbb/atalanta/AlpacaHttpTransport.java) · [POM](../pom.xml) |
| ADR-0003 — component repositories | Proposed | **Compatible**, not a license to introduce a network service or split modules |
| ADR-0004 — language-neutral event semantics | Proposed | **Not applicable** to a single-shot read-only HTTP client. No Vert.x/stream/broker required |
| ADR-0005 — records, explicit state, JSpecify/Lombok | Proposed | **Partially follows**: domain values are records, `Result`/`AlpacaError` sealed, ordinary classes used for transport/factory. **No JSpecify `@NullMarked`, `@Nullable`, Lombok or static nullness analysis yet.** S2 optional experiment, not a proven violation |
| ADR-0006 — standard async/result APIs and exploratory `Those` | Proposed | **Follows one-result use case** with `CompletionStage<Result<T>>` and `Optional`. `Those` and Herkules replacement are **hypotheses**, not approved signature changes |
| ADR-0007 — pure state transitions and effects | Proposed / exploratory | **Not applicable** to an external read-only client; strategy state machines live elsewhere |
| Atalanta issue #5 — public API refinement | **Open** | Current implementation exists but is **not PO-stabilized**. Public choices listed below need acceptance before first release |
| Atalanta issue #7 — shared fixtures | **Closed** | Java offline fixtures exist and pass; multi-language consumption belongs to [triparity #26](https://github.com/black-belt-badger/atalanta/issues/26) |

Metis ADR status was checked directly in `metis/docs/decisions/0002-*.md` through `0007-*.md`. **ADR-0002 is Accepted; ADR-0003–0007 are Proposed.** Proposed guidance cannot be retroactively treated as an implementation defect.

## Cross-cutting evidence and consumer-facing choices

| Concern | Current behavior / evidence | Audit status |
| --- | --- | --- |
| Financial precision | `BigDecimal` for money/quantities, exact Jackson decimal parsing, no financial `double` in public records. [Order](../src/main/java/bbb/atalanta/Order.java) · [transport](../src/main/java/bbb/atalanta/AlpacaHttpTransport.java#L47-L49) | **Follows** numerical precision. **S2 decision**: numerical equality is not identical to original JSON lexical spelling or `BigDecimal.equals` scale |
| Timestamp representation | Query bounds are `Instant`; provider response timestamps are mostly `String` to retain source form, offsets and fractional digits | **Decision pending S2**: choose semantic typed time versus raw textual fidelity per field |
| Missing vs null | Nullable record components + `presentFields` and `supplied(wireName)` distinguish omitted from explicit null; public record constructors can create a presence set inconsistent with values | **Follows decoder**, **S2 public construction invariant**. Discuss additive views or presence algebra, not a blanket nullability rewrite |
| Open enums | Statuses, order types/classes, asset class, position side are `String`, preserving unknown future provider values | **Intentional compatibility choice**, **S2** possible typed Known/Other companions; replacing components would be breaking |
| Provider extras | [ProviderJson](../src/main/java/bbb/atalanta/ProviderJson.java) recursively copies maps/lists, retains null and precise numbers, exposes no Jackson types | **Follows immutability objective**, but **does not** preserve raw JSON bytes, ordering or formatting. S2 optional requirement decision |
| Error algebra | [Result](../src/main/java/bbb/atalanta/Result.java) has `Ok/Err`; [AlpacaError](../src/main/java/bbb/atalanta/AlpacaError.java) has typed HTTP/IO/timeout cases; cancellation and programming faults can exceptionally complete stages | **Follows selected M3 contract.** Invalid local query arguments throw synchronously; document that distinction rather than silently wrapping everything in `Err` |
| 404 by-ID | Lookup maps `HttpFailure(404)` to successful `Optional.empty()`; other failures stay errors. [Atalanta](../src/main/java/bbb/atalanta/Atalanta.java#L103-L116) | **S2 decision pending**: library absence-as-success vs explicit error; differs from CLI presentation without necessarily differing semantically |
| HTTP safety | Fixed paper host, only GET, authenticated JDK async sender, URI encoding, 20s request / 10s connect timeout, no redirect/retry, sanitized errors. [transport](../src/main/java/bbb/atalanta/AlpacaHttpTransport.java) · [tests](../src/test/java/bbb/atalanta/AlpacaHttpTransportTest.java) | **Follows Accepted ADR-0002.** No broad implementation change justified by this review |
| Privacy | `Account`, `AccountDetails`, `Position`, `PositionUsd` and `Order` suppress sensitive `toString`; account balances and order data remain accessible to intended callers | **Follows logging-safety convention.** Public getters still expose data by design; serialization/logging responsibility remains with consumers |
| Runtime and code style | `maven.compiler.release=17`; production Jackson is internal. Package has no JSpecify annotation or Lombok. [POM](../pom.xml) · [package-info](../src/main/java/bbb/atalanta/package-info.java) | **Current intentional Java 17 target**; new Java 25/JSpecify/Lombok requirement needs an explicit compatibility decision |

## Findings: documentation defects vs hypotheses

**Confirmed S3 documentation defects (not runtime bugs):**

1. [HTTP_TRANSPORT.md](HTTP_TRANSPORT.md) still claims no public `Atalanta.account()` method and describes all endpoint adapters as future work. In reality, [Atalanta.java](../src/main/java/bbb/atalanta/Atalanta.java) already exposes five read-only methods.
2. [package-info.java](../src/main/java/bbb/atalanta/package-info.java) says the public API *will be defined after refinement*, without distinguishing **already implemented provisional API** from **not yet approved/released API**.

**Not confirmed as runtime bugs:** status/open-enum strings, nullable components, Java 17 target, 404 Optional, missing JSpecify/Lombok/`Those`, single-page result, and client-private normalized JSON. Each may be an intentional choice or a future enhancement. The 143 passing tests do not prove untested upstream permutations or cross-language parity.

## PO decision queue (do **not** implement automatically)

| Priority | Choose among | Migration / test consequences |
| --- | --- | --- |
| P1 | Keep nullable fields + `supplied`; add optional accessors; or replace with a first-class field-presence type | Record component changes break Java source/binary compatibility; test omitted/null/zero values and deep immutable extras |
| P1 | Keep open `String` enums; add typed companion accessors; or replace fields with sealed Known/Other values | Replacing record components is breaking; coordinate symbol/status vocabulary across Kirke/Medea and future unknowns |
| P1 | Keep `Ok(Optional.empty())` for lookup 404; introduce typed not-found `Err`; or provide both through explicitly named methods | Existing callers match different result shapes; regression: 404 vs 401/403/429/timeout/invalid ID |
| P2 | Retain response timestamp text or add typed time views; choose whether raw provider bytes are a public requirement | Avoid accidental precision loss or leaking private raw payloads; use shared fixtures for expected representations |
| P2 | Keep Java 17 + current `Result`; or explicitly trial JSpecify/Lombok/Herkules / alternate result types | A Java version floor or signature change impacts downstream consumers; Proposed ADRs require PO decision first |
| P2 | Keep current synchronous input validation and exceptional stages, or expose typed local validation results | Requires documented calling conventions and tests for sync exception vs async failure |
| P3 | Correct stale transport and package docs | Documentation-only change, low compatibility risk |

**No option is marked approved.** Suggested small follow-on issues *after approval*: (a) docs refresh; (b) public null/enum contract spike; (c) explicit lookup-404 and local-validation regression contract; (d) Java 17 consumer-compilation smoke tests before [release #13](https://github.com/black-belt-badger/atalanta/issues/13); (e) Kirke/Medea fixture adoption under [triparity #26](https://github.com/black-belt-badger/atalanta/issues/26). Avoid one broad refactor.

## Migration and parity maintenance

- Artifact is currently `0.1.0-SNAPSHOT`, and [release #13](https://github.com/black-belt-badger/atalanta/issues/13) remains open. Even before stable release, changing record components, method signatures, sealed variants, enum representations, error shapes or the Java runtime floor can break existing consumers. Prefer additive accessors when the chosen model allows them; otherwise document source migration.
- Shared fixture pack `alpaca-trading-read-only/1.0.0` is versioned independently of Java/Haskell/Rust releases. Record the fixture revision and hashes in each repo; use CI to detect semantic drift without requiring lockstep product versions.
- Cross-language contract checks should compare **normalized provider observations**, not Java library types against CLI ANSI text or exit codes literally. Validate intentionally different defaults and 404 behavior with explicit rationale.
- Keep this document as a **review draft**. Close #25 only after PO decisions and small approved follow-on issues are recorded; perform #26 after the remaining endpoint implementation and this review. Do not silently change Metis ADR statuses.
