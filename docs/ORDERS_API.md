# Orders endpoint contract — Java 17

**Endpoint:** authenticated `GET /v2/orders` on Alpaca's **paper Trading API**. It is a read-only list endpoint and does not place, cancel, replace, or liquidate orders.

## Public API

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

// Provider defaults: status=open, limit=50, direction=desc, no nested roll-up.
CompletionStage<Result<List<Order>>> openOrders = client.orders();

// Server-side filters, exact submitted-at boundaries, nested multi-leg results.
CompletionStage<Result<List<Order>>> filtered = client.orders(
        OrderQuery.defaults()
                .withStatus("all")
                .withLimit(100)
                .withDirection("asc")
                .withAfter(Instant.parse("2026-11-01T00:00:00Z"))
                .withUntil(Instant.parse("2026-11-13T00:00:00Z"))
                .withNested(true)
                .withSymbols(List.of("BTCUSD", "ETHUSD"))
                .withAssetClasses(List.of("crypto"))
                .withSide("buy"));

// The mutually exclusive cursor alternative.
CompletionStage<Result<List<Order>>> nextBatch = client.orders(
        OrderQuery.defaults().withAfterOrderId(previousOrderId));
```

The `OrderQuery` and `Order` types are immutable Java 17 records. Jackson and HTTP types remain package-private implementation details; no Vavr or other FP runtime dependency. Asynchronous operations use `CompletionStage<Result<List<Order>>>` with typed `AlpacaError` values for operational failure.

## Request coverage matrix

| API parameter | Alpaca default / semantics | Atalanta behavior |
|---|---|---|
| `status` | `open`, permits `open`, `closed`, `all` | Omit by default; `withStatus(...)` validates |
| `limit` | `50`, range 1–500 | Omit by default; validate 1 and 500 inclusive |
| `after` | Exclusive submitted-at time, ISO 8601 | `withAfter(Instant)` |
| `until` | Exclusive submitted-at time, ISO 8601 | `withUntil(Instant)`; if both set, after must precede until |
| `direction` | `desc`, permits `asc` and `desc` | Omit by default; `withDirection(...)` validates |
| `nested` | Unset/false; roll child legs under primary order | `withNested(Boolean)` preserves explicit true or false |
| `symbols` | Optional comma-separated symbols | `withSymbols(List<String>)` validates/joins; crypto uses `BTCUSD` rather than `BTC/USD` |
| `side` | `buy` or `sell` | `withSide(...)` validates |
| `asset_class` | Optional comma-separated asset classes | `withAssetClasses(List<String>)`, supports future safe names |
| `before_order_id` | Exclusive UUID cursor | `withBeforeOrderId(UUID)` |
| `after_order_id` | Exclusive UUID cursor | `withAfterOrderId(UUID)` |

**Incompatibility rules:** do not mix `before_order_id` and `after_order_id`, and do not combine **either** order-ID anchor with `after` or `until`. The immutable query constructor validates these constraints immediately, before any HTTP request. There is no `page_token` parameter for this endpoint. Only one server page is returned per API call; the library does not silently fetch multiple pages or retry.

Parameter values are encoded only once by the shared HTTP transport. A successful empty collection returns an immutable empty list, not an error.

## Response field coverage matrix

| Provider properties | Public `Order` record fields | Notes |
|---|---|---|
| `id` | `UUID id` | Required and validated |
| `client_order_id`, `asset_id` | String, UUID | Optional |
| `symbol`, `asset_class`, `side`, `position_intent` | String | Optional on some parent/group orders; future values retained |
| `status`, `type`, `order_type`, `order_class`, `time_in_force` | String | Status required; provider new statuses/classes remain readable |
| `qty`, `notional`, `filled_qty`, `filled_avg_price` | BigDecimal | Exact quantities and fills; null quantity on notional orders |
| `limit_price`, `stop_price`, `trail_price`, `trail_percent`, `hwm` | BigDecimal | Nullable, exact trailing and price fields |
| `extended_hours` | Boolean | Optional or explicit null |
| `created_at`, `updated_at`, `submitted_at`, `filled_at` | String | Optional high-precision timestamp text retained |
| `expired_at`, `canceled_at`, `failed_at`, `replaced_at`, `expires_at` | String | Optional event timestamps |
| `replaced_by`, `replaces` | UUID | Optional order-replacement links |
| `group_id`, `group_role`, `source`, `subtag` | UUID / String | Optional grouped/provider extension fields |
| `legs` | Immutable `List<Order>` | Nested primary order leg array; null/missing distinguished with `supplied("legs")` |
| Future undocumented fields | Immutable `Map<String,Object> additionalFields` | Deep standard-Java maps/lists, nulls, BigDecimal/BigInteger, text and booleans |
| Original field presence | `Set<String> presentFields`, `supplied(String)` | Distinguishes missing from explicit null and numeric zero |

The `Order` record covers a wide range of current fields while remaining forward-compatible. Decimal quantities and prices are read exactly; Java `double` and `float` are not used. The decoder accepts null or omitted optional fields and validates UUIDs, decimals, booleans and leg shapes. Child legs can be decoded one level deep; nested nonempty grandchildren are rejected as an invalid response.

`Order.toString()` is redacted, so accidental logging does not expose order quantities, identifiers or account-linked data. Unknown provider data remains accessible semantically as plain Java values; **raw Jackson nodes and byte-for-byte HTTP JSON are deliberately not a public feature**, per API decision [#5](https://github.com/black-belt-badger/atalanta/issues/5).

## Error handling

Routine authentication/permission failures, 429 rate limits, non-success HTTP statuses, network failures and timeouts return `Result.Err(AlpacaError)`. Malformed JSON, invalid root/leg shapes or erroneous scalar field types return `InvalidResponse` without exposing provider response bodies or private identifiers. Unexpected programming failures and cancellation may complete the stage exceptionally rather than being disguised as routine API errors.

## Synthetic offline acceptance tests

- Full partially filled equity limit order with high-precision decimals and timestamps.
- Crypto notional-based market order (nullable quantity), fractional fill and empty legs.
- Multi-leg options with null parent side and two typed order legs.
- Bracket order with stop child; trailing stop with HWM/trail fields.
- Unrecognized future enum strings and nested extension properties with exact decimal numbers.
- Explicit filters, upper/lower limit boundaries, all date bounds, both exclusive cursors, invalid combinations, symbol/asset-class CSV encoding, and validated defaults.
- Empty result, malformed members/leg arrays, null/missing fields, security redaction, async request/response and HTTP failures.
- No real Alpaca credentials, trading actions, network services or private account records in CI.

## References and paired issues

- [Alpaca Get All Orders](https://docs.alpaca.markets/us/reference/getallorders-1)
- [Alpaca Orders and Lifecycle](https://docs.alpaca.markets/us/docs/orders-at-alpaca)
- [Kirke M3 order-list contract #35](https://github.com/black-belt-badger/kirke/issues/35)
- [Medea M3 order-list contract #36](https://github.com/black-belt-badger/medea/issues/36)

This is only the list endpoint. `GET /v2/orders/{order_id}` is a **separate** planned endpoint, tracked in [Atalanta #12](https://github.com/black-belt-badger/atalanta/issues/12).
