# Changelog for Atalanta

All notable user-visible changes to the Atalanta Java library will be recorded here. Versioning and publication will be refined in [issue #13](https://github.com/black-belt-badger/atalanta/issues/13).

## Unreleased

### Added
- Java 17 Maven JAR skeleton, JUnit 5/AssertJ smoke test, CI, BSD-3-Clause license and README.
- Repository governance, contributor guidance, issue/PR templates and security reporting policy.
- Sealed Java 17 `Result<T>` and `AlpacaError` records, useful FP combinators, and offline tests.
- Internal asynchronous paper-only HTTP transport with protected authentication, exact-decimal Jackson parsing, typed failures, redirect protection and deterministic offline tests.
- First public read-only endpoint: `Atalanta.paper(...).account()`, returning async `Result<Account>`, exact-decimal account records, complete documented account fields, forward-compatible extension properties and synthetic contract tests.
- Second read-only endpoint: `Atalanta.assets(AssetQuery)`, honoring Alpaca's equity defaults and providing explicit crypto/status/exchange/attribute filters, typed asset records, exact decimals, legacy/current field support and synthetic fixtures.
- Shared internal provider JSON conversion for preserving unknown account/asset fields without exposing Jackson.
- Third read-only endpoint: `Atalanta.positions()`, returning asynchronous typed open positions with precise fractional holdings and profit/loss, nested local-currency USD valuations, IPO/option nullability, forward-compatible extension fields and offline contract fixtures.
- Fourth read-only endpoint: `Atalanta.orders(OrderQuery)`, with documented list parameters, exclusive order-ID pagination, strict validation, typed order and nested-leg records, exact partial/notional amounts, and offline contract tests.
- Fifth read-only endpoint: `Atalanta.order(UUID[, boolean])`, with optional nested-leg roll-up, UUID validation, typed order decoding shared with the list endpoint, optional-not-found semantics and offline tests.
- Exact `ratio_qty` (`BigDecimal`) added to the shared order record for multi-leg options.
- Portable, versioned, self-contained read-only Alpaca Trading API contract pack: 26 neutral HTTP/response/expectation cases for five endpoints, canonical synthetic fixtures, SHA-256 hashes and a JUnit runner. Moved existing fixtures rather than maintaining duplicate copies; rebuilt mixed fixtures without rounding precise numeric JSON.

### Limitations
- Five paper-only GET endpoints are implemented. Published Maven artifact, client-order-ID lookup, and order-writing operations remain outstanding.
