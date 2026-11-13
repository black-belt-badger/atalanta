<p align="center">
  <img src="docs/fancy-readme.webp" alt="Atalanta — bow and arrow monogram and wordmark" width="760">
</p>

# Atalanta — Java 17 client library for Alpaca's APIs

**Atalanta** is a planned, independent Java library for [Alpaca's Trading and Market Data APIs](https://docs.alpaca.markets/), developed alongside [Kirke](https://github.com/black-belt-badger/kirke) (Haskell) and [Medea](https://github.com/black-belt-badger/medea) (Rust). The projects will aim for functional parity while retaining idiomatic language-specific designs.

**Current status: Java 17 library foundation with internal asynchronous HTTP transport.** The build, CI, sealed `Result<T>` and `AlpacaError` types, and GET-only paper Trading API HTTP/JSON transport are implemented; there are **no public Alpaca endpoints**, published packages, or trading operations yet. The consumer-facing Java API is being refined alongside Kirke and Medea.

## Requirements

- **JDK 17 or newer** to build; Maven compiles for the Java 17 platform via `--release 17`. The GitHub CI workflow builds with JDK 17 itself.
- **Apache Maven 3.8.7 or newer**.

## Build and test

```sh
git clone https://github.com/black-belt-badger/atalanta.git
cd atalanta
mvn --batch-mode --no-transfer-progress verify
```

For an explicit JDK 17 build on Debian/Hoopoe:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn --batch-mode --no-transfer-progress verify
```

The build produces `target/atalanta-0.1.0-SNAPSHOT.jar`. Maven coordinates are currently `io.github.blackbeltbadger:atalanta:0.1.0-SNAPSHOT`; the public dependency contract is not finalized.

Tests use **JUnit 5** and **AssertJ**. The initial smoke test verifies that the Java 17 language level and test infrastructure work; actual provider behavior will be tested when implemented. No API credentials, external services or database are required.

## Functional error types

Atalanta defines its own Java 17 algebraic data types, with no third-party FP dependency:

```java
Result<Integer> value = Result.ok(21).map(n -> n * 2);
Result<Integer> failure = Result.err(new AlpacaError.RateLimited("Rate limited"));
```

`Result<T>` is a sealed interface with immutable `Ok<T>` and `Err<T>` records, `map`, `flatMap` and `fold`. `AlpacaError` is a sealed family of typed operational failures. Future network operations will return `CompletionStage<Result<T>>`; **no HTTP endpoint has been implemented yet**. Neither Jackson nor a JSON representation appears in these public signatures. Error descriptions are sanitized and must never contain secrets or private responses.

## Internal HTTP transport

An internal, **asynchronous**, GET-only JDK 17 HTTP transport now handles Alpaca paper Trading API authentication and typed operational failures. Redirects and automatic retries are disabled. Jackson decodes and validates JSON **internally**, preserving exact decimals; no Jackson types appear in the public API.

The transport is intentionally not an endpoint method and cannot yet be called through a public `Atalanta` client. See [transport design and offline tests](docs/HTTP_TRANSPORT.md) for its boundaries and an example of future endpoint integration.

## Artwork

The approved project banner is displayed above. Other artwork variants are stored in [`docs/`](docs/) and are intentionally not embedded in the README, matching Kirke and Medea.

## License

[BSD 3-Clause](LICENSE), matching Kirke and Medea.

Atalanta is an unofficial project and is not affiliated with, sponsored by, or endorsed by Alpaca Markets.
