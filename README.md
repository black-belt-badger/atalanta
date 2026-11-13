<p align="center">
  <img src="docs/fancy-readme.webp" alt="Atalanta — bow and arrow monogram and wordmark" width="760">
</p>

# Atalanta — Java 17 client library for Alpaca's APIs

**Atalanta** is a planned, independent Java library for [Alpaca's Trading and Market Data APIs](https://docs.alpaca.markets/), developed alongside [Kirke](https://github.com/black-belt-badger/kirke) (Haskell) and [Medea](https://github.com/black-belt-badger/medea) (Rust). The projects will aim for functional parity while retaining idiomatic language-specific designs.

**Current status: five read-only API endpoints implemented.** Atalanta provides asynchronous `GET /v2/account`, `GET /v2/assets`, `GET /v2/positions`, `GET /v2/orders` and `GET /v2/orders/{order_id}` for Alpaca **paper** accounts, with typed Java 17 records, exact decimal values, and our sealed `Result<T>`/`AlpacaError` failure model. Published Maven packages and order-writing operations are not yet available.

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

Tests use **JUnit 5** and **AssertJ**, including synthetic account/assets/positions/orders contract fixtures and asynchronous HTTP stubs. No API credentials, external services or database are required to run the tests.

## Account API (read-only paper trading)

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CompletionStage<Result<Account>> result = client.account();
```

`Account` and `AccountDetails` are Java records. Money and fractional quantities use `BigDecimal` rather than binary floating-point. The client preserves optional/missing fields, unknown account status strings and new provider properties using immutable standard Java values; no Jackson classes enter public signatures. Do not log account numbers or full account payloads.

See [account field coverage and fixtures](docs/ACCOUNT_API.md). Actual HTTP calls require **Alpaca paper credentials**.

## Assets API (read-only paper trading)

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

// Provider defaults: US equities, all statuses.
CompletionStage<Result<List<Asset>>> equities = client.assets();

// Explicitly select crypto; the Java library does not override Alpaca defaults.
CompletionStage<Result<List<Asset>>> activeCrypto =
        client.assets(AssetQuery.crypto().withStatus("active"));

// Server-side filters: NASDAQ/NYSE assets matching any selected attribute.
CompletionStage<Result<List<Asset>>> filtered =
        client.assets(AssetQuery.defaults()
                .withExchanges(List.of("NASDAQ", "NYSE"))
                .withAttributes(List.of("has_options", "overnight_tradable")));
```

`Asset` and `AssetQuery` are immutable Java records, including precise crypto quantity and price increments, exchange/attribute filtering, and forward-compatible asset classes. No JSON implementation types are exposed. See the [assets contract and field coverage](docs/ASSETS_API.md).

## Open positions API (read-only paper trading)

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CompletionStage<Result<List<Position>>> positions = client.positions();
```

`GET /v2/positions` retrieves **all currently open positions** with no query parameters. `Position` and `PositionUsd` are Java records; quantities, P/L and exchange rates use exact `BigDecimal` values. A valid empty portfolio returns an empty immutable list. Nullable IPO prices, option positions with empty exchange codes, local-currency USD valuations, and future fields are supported without exposing Jackson. Position records redact holdings in `toString()`.

See the [position coverage matrix, example fixtures and validation rules](docs/POSITIONS_API.md). This is not a historical-positions API or a position-closing operation.

## Orders API (read-only paper trading)

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

// Provider defaults: open, 50 results, newest first.
CompletionStage<Result<List<Order>>> openOrders = client.orders();

// Include closed orders and full multi-leg option structures.
CompletionStage<Result<List<Order>>> allOrders =
        client.orders(OrderQuery.defaults()
                .withStatus("all")
                .withLimit(100)
                .withNested(true));

// Exclusive order-ID pagination (do not combine with timestamp boundaries).
CompletionStage<Result<List<Order>>> nextPage =
        client.orders(OrderQuery.defaults().withAfterOrderId(previousOrderId));
```

`Order` and `OrderQuery` are immutable Java records; prices, quantities and partial fills are exact `BigDecimal` values. The list API supports all documented filters, including date limits, symbols, asset classes and mutually exclusive order-ID cursors. Nested option legs are typed order records. No order placement, cancellation or live trading methods are implemented.

See [orders request/response coverage, constraints and offline fixtures](docs/ORDERS_API.md).

## Get order by ID (read-only paper trading)

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CompletionStage<Result<Optional<Order>>> order = client.order(orderId);
CompletionStage<Result<Optional<Order>>> nested = client.order(orderId, true);
```

Both methods return our existing typed `Order` record, including exact decimal prices and quantities and typed option legs. A valid 404 is `Ok(Optional.empty())`; authentication, rate limit, network and malformed response failures remain typed `Err` values. An omitted `nested` flag is different from sending explicit `nested=false`. The order-list decoder is reused for consistent behavior.

See [order-by-ID contract and tests](docs/ORDER_BY_ID_API.md). This is a UUID lookup, not a `client_order_id` lookup, and no order mutation is supported.

## Functional error types

Atalanta defines its own Java 17 algebraic data types, with no third-party FP dependency:

```java
Result<Integer> value = Result.ok(21).map(n -> n * 2);
Result<Integer> failure = Result.err(new AlpacaError.RateLimited("Rate limited"));
```

`Result<T>` is a sealed interface with immutable `Ok<T>` and `Err<T>` records, `map`, `flatMap` and `fold`. `AlpacaError` is a sealed family of typed operational failures. Account, assets, positions and orders all return asynchronous `CompletionStage<Result<T>>` values using our sealed functional error type. The single-order lookup additionally uses `Optional<Order>` to represent an order not found. Neither Jackson nor a JSON representation appears in these public signatures. Error descriptions are sanitized and must never contain secrets or private responses.

## Internal HTTP transport

An internal, **asynchronous**, GET-only JDK 17 HTTP transport now handles Alpaca paper Trading API authentication and typed operational failures. Redirects and automatic retries are disabled. Jackson decodes and validates JSON **internally**, preserving exact decimals; no Jackson types appear in the public API.

The transport remains package-private; applications call the public `Atalanta` client. See [transport design and offline tests](docs/HTTP_TRANSPORT.md) for its boundaries.

## Artwork

The approved project banner is displayed above. Other artwork variants are stored in [`docs/`](docs/) and are intentionally not embedded in the README, matching Kirke and Medea.

## License

[BSD 3-Clause](LICENSE), matching Kirke and Medea.

Atalanta is an unofficial project and is not affiliated with, sponsored by, or endorsed by Alpaca Markets.
