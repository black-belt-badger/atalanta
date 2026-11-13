<p align="center">
  <img src="docs/fancy-readme.webp" alt="Atalanta — bow and arrow monogram and wordmark" width="760">
</p>

# Atalanta — Java 17 client library for Alpaca's APIs

**Atalanta** is a planned, independent Java library for [Alpaca's Trading and Market Data APIs](https://docs.alpaca.markets/), developed alongside [Kirke](https://github.com/black-belt-badger/kirke) (Haskell) and [Medea](https://github.com/black-belt-badger/medea) (Rust). The projects will aim for functional parity while retaining idiomatic language-specific designs.

**Current status: five read-only Trading API endpoints, historical crypto bars/trades/quotes, and explicit single-order paper submission and cancellation.** Atalanta supports asynchronous `GET /v2/account`, `GET /v2/assets`, `GET /v2/positions`, `GET /v2/orders`, `GET /v2/orders/{order_id}`, and historical `GET /v1beta3/crypto/{loc}/bars` and `GET /v1beta3/crypto/{loc}/trades` and `GET /v1beta3/crypto/{loc}/quotes` via the separate Alpaca Market Data host. Only `submitPaperOrder(ConfirmedPaperOrder)` sends a paper-only `POST /v2/orders`; only `cancelPaperOrder(UUID)` sends a paper-only `DELETE /v2/orders/{order_id}`. Read operations never mutate an order. Typed Java 17 records, exact decimals and sealed `Result<T>`/`AlpacaError` semantics are used throughout. There is no live trading, automatic cancellation, cancel-all operation, or published Maven release.

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

The versioned [portable Trading API contract pack](contracts/alpaca-trading/v1/README.md) contains 26 synthetic request/response/expected-outcome cases and SHA-256 integrity hashes. Java JUnit tests exercise all five public endpoints against the pack. The [phase-1 triparity audit](docs/TRIPARITY_AUDIT.md) and [reproducible Haskell/Rust decoder smoke checks](tools/triparity/README.md) found three shared fixture mismatches in each CLI; full native normalized request/response parity remains open under Atalanta #26.

**After the M4/M5 feature merges:** the [post-feature consistency and triparity review](docs/POST_FEATURE_CONSISTENCY_AUDIT.md) covers all ten endpoint operations, identifies remaining shared-fixture decoder gaps and records decisions still needed before the first library release. This is an audit draft, **not an approved redesign**.

## Historical crypto bars (read-only Market Data)

Historical crypto OHLCV bars are fetched from the **separate, fixed** `data.alpaca.markets` HTTPS host. The public Java 17 API is asynchronous, without a CLI or mutable global client configuration:

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoBarQuery query = CryptoBarQuery.of(
        List.of("BTC/USD", "ETH/USD"), CryptoTimeframe.minutes(1))
        .withStart("2026-10-02T00:00:00Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withLimit(1000)
        .withSort(CryptoBarQuery.Sort.ASC);

CompletionStage<Result<CryptoBarPage>> page = client.cryptoBars(query);
CompletionStage<CryptoBarScan> bounded = client.cryptoBarsPages(query, 3);
```

`cryptoBars` fetches **one page**, exposing its opaque `nextPageToken()`. The bounded multi-page method fetches at most 20 pages; `Stopped` indicates exhaustion or a remaining token, and `Failed` retains successful pages alongside the terminal typed error. Both handle short/empty pages with continuation tokens. The `CryptoBar` record exposes exact `BigDecimal` OHLCV, optional `BigInteger` trade count and VWAP, high-precision original timestamp text and immutable unknown-provider fields. All five documented locations and provider timeframe variants are supported.

Read the [complete historical crypto-bars API, request/response contract and offline-test guide](docs/CRYPTO_BARS_API.md). No broker order is created and no account state is changed.

## Historical crypto trades (read-only Market Data)

Historical individual trades reuse Atalanta's fixed, read-only Alpaca Market Data HTTPS transport. This Java 17 API exposes exact `BigInteger` IDs, `BigDecimal` prices/sizes, original nanosecond-resolution timestamps, optional taker-side values, and immutable future-provider fields. Trades do **not** have a timeframe parameter.

```java
CryptoTradeQuery query = CryptoTradeQuery.of(List.of("BTC/USD", "ETH/USD"))
        .withLocation(CryptoLocation.US)
        .withStart("2026-10-02T00:00:00.123456789Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withLimit(1000)
        .withSort(CryptoTradeQuery.Sort.ASC);

CompletionStage<Result<CryptoTradePage>> page = client.cryptoTrades(query);
CompletionStage<CryptoTradeScan> bounded = client.cryptoTradesPages(query, 3);
```

`cryptoTrades` fetches exactly **one provider page**, exposing its opaque continuation. `cryptoTradesPages` fetches at most 20 pages and either reports `Stopped` with a remaining token or `Failed` with successfully retrieved pages and a typed error. Even a short/empty page can have a continuation. The Alpaca limit applies **across all symbols**, not separately to each.

See [historical crypto-trades contract, query/response models and offline tests](docs/CRYPTO_TRADES_API.md). This is not a trading order endpoint and never modifies paper account state.

## Historical crypto quotes (read-only Market Data)

Best-bid/best-ask observations use the same fixed Market Data host as Bars and have **no timeframe parameter**:

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoQuoteQuery query = CryptoQuoteQuery.of(List.of("BTC/USD", "ETH/USD"))
        .withStart("2026-10-02T00:00:00.123456789Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withLimit(1000)
        .withSort(CryptoQuoteQuery.Sort.ASC);

CompletionStage<Result<CryptoQuotePage>> page = client.cryptoQuotes(query);
CompletionStage<CryptoQuoteScan> bounded = client.cryptoQuotesPages(query, 3);
```

`CryptoQuote` stores the five fields `ap`, `as`, `bp`, `bs` and `t` as **exact BigDecimal prices/sizes and original high-resolution timestamp text**, with immutable extensions. Bid and ask sizes use the **base asset**, not an exchange lot multiplier. Provider defaults are preserved when optional query filters are omitted. Pagination is bounded, follows continuation tokens even after short pages, detects loops and preserves earlier valid pages on failure. No order mutation occurs. See the [historical crypto quotes contract and offline tests](docs/CRYPTO_QUOTES_API.md).

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

`Order` and `OrderQuery` are immutable Java records; prices, quantities and partial fills are exact `BigDecimal` values. The list API supports all documented filters, including date limits, symbols, asset classes and mutually exclusive order-ID cursors. Nested option legs are typed order records. These reads never cancel an order; single-order paper cancellation is separate and explicit, and live trading is not supported.

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

See [order-by-ID contract and tests](docs/ORDER_BY_ID_API.md). This is a UUID lookup, not a `client_order_id` lookup; it never mutates an order.

## Submit a crypto paper order (explicit mutation)

**This is a real PAPER order submission, not a preview or a guarantee of a fill.** The validated request and authorization values are pure; only calling `submitPaperOrder` sends HTTP:

```java
Atalanta paper = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoOrderRequest prepared = CryptoOrderRequest.marketQuantity(
        "BTC/USD", CryptoOrderRequest.Side.BUY,
        CryptoOrderRequest.TimeInForce.GTC,
        new BigDecimal("0.000000001"), "stable-paper-order-001");

ConfirmedPaperOrder confirmed = prepared.authorize(); // pure, no network
CompletionStage<Result<Order>> submitted = paper.submitPaperOrder(confirmed); // REAL POST
```

The API also provides `marketNotional` and `limitQuantity` factories, supporting exact positive `BigDecimal` values, required and mutually exclusive qty/notional, USD-notional precision, only `gtc`/`ioc` and a caller-controlled stable client order ID. A returned `Order` carries Alpaca's current status and is **not necessarily filled**. A timeout, broken connection, provider 5xx or unverifiable 2xx response is an `AlpacaError.SubmissionOutcomeUnknown`; inspect existing paper orders by `client_order_id` before considering any retry. The client never silently retries, targets a live host or auto-submits through reads. Tests never issue real POSTs. See the [paper order submission API and recovery contract](docs/PAPER_SUBMIT_API.md).

## Cancel one paper order (explicit mutation)

**This call attempts a real cancellation of one PAPER order; it is not a dry run or a final-status guarantee.** Use the provider-issued UUID, not the client order ID. No read-only method performs implicit cancellation.

```java
Atalanta paper = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));
UUID id = UUID.fromString("9275f66a-0a1b-4a22-8c55-167416c7cf14");

// Explicit single-order DELETE to the fixed Alpaca PAPER host.
CompletionStage<Result<PaperCancellationAccepted>> accepted =
        paper.cancelPaperOrder(id);

// HTTP 204 only means accepted. Check final state separately.
CompletionStage<Result<Optional<Order>>> status = paper.order(id);
```

The UUID is synthetic. Use a real server ID **only when you intend to cancel it**. A `PaperCancellationAccepted` value does not mean `Order.status() == "canceled"`; an order may fill before cancellation completes. HTTP 404 (not found) and 422 (not cancelable) have distinct typed errors; timeouts, network problems and provider 5xx produce an **uncertain outcome**, not an excuse to retry automatically. Tests never send real cancellation requests. See the [cancellation contract and recovery guidance](docs/PAPER_CANCEL_API.md).

## Functional error types

Atalanta defines its own Java 17 algebraic data types, with no third-party FP dependency:

```java
Result<Integer> value = Result.ok(21).map(n -> n * 2);
Result<Integer> failure = Result.err(new AlpacaError.RateLimited("Rate limited"));
```

`Result<T>` is a sealed interface with immutable `Ok<T>` and `Err<T>` records, `map`, `flatMap` and `fold`. `AlpacaError` is a sealed family of typed operational failures. Account, assets, positions and orders all return asynchronous `CompletionStage<Result<T>>` values using our sealed functional error type. The single-order lookup additionally uses `Optional<Order>` to represent an order not found. Neither Jackson nor a JSON representation appears in these public signatures. Error descriptions are sanitized and must never contain secrets or private responses.

## Internal HTTP transport

An internal, **asynchronous** JDK 17 HTTP transport handles paper Trading API authentication and typed operational failures. Five reads use GET; separate methods issue one explicitly authorized paper POST or UUID-specific DELETE, without redirect following or application-level retries. Jackson encodes/decodes JSON **internally**; no Jackson types appear in the public API.

The transport remains package-private; applications call the public `Atalanta` client. See [transport design and offline tests](docs/HTTP_TRANSPORT.md) for its boundaries.

## Artwork

The approved project banner is displayed above. Other artwork variants are stored in [`docs/`](docs/) and are intentionally not embedded in the README, matching Kirke and Medea.

## License

[BSD 3-Clause](LICENSE), matching Kirke and Medea.

Atalanta is an unofficial project and is not affiliated with, sponsored by, or endorsed by Alpaca Markets.
