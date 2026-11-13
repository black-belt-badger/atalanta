# Changelog for Atalanta

All notable user-visible changes to the Atalanta Java library will be recorded here. Versioning and publication will be refined in [issue #13](https://github.com/black-belt-badger/atalanta/issues/13).

## Unreleased

### Added
- Java 17 Maven JAR skeleton, JUnit 5/AssertJ smoke test, CI, BSD-3-Clause license and README.
- Repository governance, contributor guidance, issue/PR templates and security reporting policy.
- Sealed Java 17 `Result<T>` and `AlpacaError` records, useful FP combinators, and offline tests.
- Internal asynchronous paper-only HTTP transport with protected authentication, exact-decimal Jackson parsing, typed failures, redirect protection and deterministic offline tests.

### Limitations
- No public Java client API, Alpaca endpoints, published Maven artifact, or trading behavior yet.
